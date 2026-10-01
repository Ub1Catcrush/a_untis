package com.webuntis.dashboard.api

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.webuntis.dashboard.R
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.Message
import com.webuntis.dashboard.model.periodNumberFor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

/**
 * Periodic background check (see [NotificationScheduler]) that looks for schedule changes,
 * new messages, new homework, and new classbook entries since the last check, and posts a
 * local notification for anything new — see [NotificationHelper].
 *
 * Entirely local-diff based: there is no push backend here (WebUntis doesn't offer one to
 * third-party apps), so this polls on a periodic WorkManager job instead. Deliberately
 * conservative about network/battery use — a single combined check per run, skips entirely
 * when the feature is off or no session/credentials are available, and the very first run
 * only establishes a baseline (no notification flood for things that already existed before
 * the feature was turned on).
 *
 * Dedup strategy (see [ChangeSnapshot]): a per-category "current state" comparison decides
 * *what* changed, but whether that change actually triggers a notification is gated by the
 * [ChangeSnapshot.notifiedAt] ledger, which remembers every key we've already notified about
 * for 7 days. The snapshot is persisted after *each* category, not just once at the end of
 * [doWork] — so if e.g. the classbook check throws, the timetable/messages/homework results
 * already computed this run are not lost and won't be re-notified next cycle.
 */
@HiltWorker
class PlanChangeCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: WebUntisRepository,
    private val sessionManager: SessionManager,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    private val gson = Gson()
    private val tag = "PlanChangeCheckWorker"

    override suspend fun doWork(): Result {
        if (!sessionManager.notificationsEnabled) {
            Log.i(tag, "Skipping: notifications disabled in settings")
            return Result.success()
        }
        // No session, or never logged in with "remember me" — nothing we can silently
        // re-authenticate with in the background (see SessionManager.storedCredentials).
        if (sessionManager.session == null || sessionManager.storedCredentials == null) {
            Log.i(tag, "Skipping: no session (session=${sessionManager.session != null}, storedCredentials=${sessionManager.storedCredentials != null})")
            return Result.success()
        }

        return try {
            notificationHelper.ensureChannels()

            val previous = readSnapshot()
            val isFirstRun = previous == null
            if (isFirstRun) {
                Log.i(tag, "First run (no stored snapshot — fresh install, cleared data, or unparseable snapshot). Establishing baseline; only items dated within the last $RECENT_WINDOW_DAYS day(s) can notify this cycle.")
            }
            // Mutable "working copy" we persist after every category, so a failure partway
            // through doesn't roll back categories that already succeeded this run.
            var snapshot = pruneExpired(previous ?: ChangeSnapshot())

            // Timetable is the one category with no usable "when was this change made"
            // signal — a lesson only carries its own date, not when it got cancelled — so
            // there's no way to tell a change made 5 minutes ago from one made last month.
            // It therefore stays fully suppressed on a first run (notifying every existing
            // cancellation in the next 5 days would be a flood). The other three categories
            // carry real dates, so they use the recency window instead of blanket suppression.
            snapshot = checkTimetable(snapshot, notify = !isFirstRun)
            persist(snapshot)

            snapshot = checkMessages(snapshot, isFirstRun)
            persist(snapshot)

            snapshot = checkHomework(snapshot, isFirstRun)
            persist(snapshot)

            snapshot = checkClassbook(snapshot, isFirstRun)
            persist(snapshot)

            Log.i(tag, "Check complete: ${snapshot.lessonStatus.size} lessons/${snapshot.messageIds.size} messages/" +
                "${snapshot.homeworkIds.size} homework/${snapshot.classbookIds.size} classbook entries tracked, " +
                "${snapshot.notifiedAt.size} keys in the 7-day dedup ledger")
            Result.success()
        } catch (e: Exception) {
            Log.w(tag, "Background check failed, will retry next cycle", e)
            // Not Result.retry(): a failed network call here shouldn't cause WorkManager's
            // backoff to spin the job more often than its normal periodic interval. Whatever
            // was already persisted via persist() above (per-category) survives regardless.
            Result.success()
        }
    }

    private fun readSnapshot(): ChangeSnapshot? =
        ChangeSnapshot.parse(sessionManager.lastNotifiedSnapshot, gson)

    private fun persist(snapshot: ChangeSnapshot) {
        sessionManager.lastNotifiedSnapshot = gson.toJson(snapshot)
    }

    /** Drops ledger/log entries older than the 7-day TTL so the persisted blob doesn't grow
     *  forever and so a key becomes eligible for a fresh notification again after 7 days. */
    private fun pruneExpired(snapshot: ChangeSnapshot): ChangeSnapshot {
        val cutoff = System.currentTimeMillis() - ChangeSnapshot.NOTIFIED_TTL_MS
        return snapshot.copy(
            notifiedAt = snapshot.notifiedAt.filterValues { it >= cutoff },
            recentChanges = snapshot.recentChanges
                .filter { it.timestampMs >= cutoff }
                .sortedByDescending { it.timestampMs }
                .take(ChangeSnapshot.MAX_RECENT_CHANGES)
        )
    }

    /** True if we have NOT already notified about [key] within the last 7 days. */
    private fun ChangeSnapshot.isFresh(key: String): Boolean {
        val last = notifiedAt[key] ?: return true
        return System.currentTimeMillis() - last >= ChangeSnapshot.NOTIFIED_TTL_MS
    }

    private fun ChangeSnapshot.withNotified(keys: List<String>, log: List<ChangeLogEntry>): ChangeSnapshot {
        if (keys.isEmpty() && log.isEmpty()) return this
        val now = System.currentTimeMillis()
        return copy(
            notifiedAt = notifiedAt + keys.associateWith { now },
            recentChanges = (log + recentChanges)
                .sortedByDescending { it.timestampMs }
                .take(ChangeSnapshot.MAX_RECENT_CHANGES)
        )
    }

    // Stable-ish key: server-side lesson id plus date. Deliberately does NOT include
    // subjectName — that can be represented differently between polls for a substituted /
    // cancelled lesson (e.g. original vs. replacement subject), which previously made the
    // same real-world change look "new" again on the next check and re-notify endlessly.
    private fun lessonKey(lesson: Lesson): String = "${lesson.id}-${lesson.date}-${lesson.startTime}"
    private fun lessonState(lesson: Lesson): String = when {
        lesson.isCancelled                      -> "cancelled"
        // A genuine subject swap (e.g. "Deutsch statt Mathematik") — WebUntisRepository's
        // lesson-merge sets replacedSubject whenever a different subject fills a cancelled
        // slot, and *also* sets lstype="subst" for it, so this check has to come before the
        // plain isSubstitution one below or a subject change would just be reported as an
        // unremarkable "Vertretung: Deutsch" with no hint that the subject itself changed.
        !lesson.replacedSubject.isNullOrBlank() -> "subjectchange"
        lesson.isSubstitution                   -> "subst"
        lesson.isRoomChange                     -> "roomchange"
        else                                     -> "normal"
    }

    /** Only lessons in the near future are worth notifying about — a change to a lesson from
     *  last week (e.g. re-fetched while backfilling enrichment) shouldn't resurface. */
    private suspend fun checkTimetable(baseline: ChangeSnapshot, notify: Boolean): ChangeSnapshot {
        val daysResult = repository.getSchoolDaysFrom(LocalDate.now(), numDays = 5, forceRefresh = true)
        val days = daysResult.getOrNull()
        if (days == null) {
            Log.w(tag, "checkTimetable: fetch failed, keeping previous baseline — ${daysResult.exceptionOrNull()}")
            return baseline
        }
        val lessons = days.flatMap { it.lessons }

        val current = lessons.associate { lessonKey(it) to lessonState(it) }
        // Only keep near-future keys going forward — an unbounded map would grow forever
        // as dates roll by.
        var result = baseline.copy(lessonStatus = current)
        if (!notify) return result

        val changed = current.filter { (key, state) ->
            state != "normal" && baseline.lessonStatus[key] != state
        }
        // Gate on the 7-day dedup ledger too: a key we already notified about recently is
        // skipped even if it looks "changed" against the (possibly stale/partial) state map.
        val toNotify = changed.filterKeys { key -> baseline.isFresh("timetable:$key") }
        Log.i(tag, "checkTimetable: ${lessons.size} lessons fetched, ${changed.size} changed vs. last check, ${toNotify.size} not already notified in the last 7 days")
        if (toNotify.isEmpty()) return result

        // Only fetched when actually needed (i.e. there's something to notify about this run)
        // — cheap/cached already via WebUntisRepository, but no point paying for it on the
        // far more common "nothing changed" runs.
        val periodLookup = repository.getTimegrid(forceRefresh = false).getOrDefault(emptyList())

        val bySubjectLesson = lessons.associateBy { lessonKey(it) }
        val notifiedKeys = mutableListOf<String>()
        val log = mutableListOf<ChangeLogEntry>()
        val now = System.currentTimeMillis()

        if (toNotify.size > 4) {
            notificationHelper.notifyLessonChangesSummary(toNotify.size)
        }
        toNotify.entries.forEachIndexed { i, (key, state) ->
            val lesson = bySubjectLesson[key] ?: return@forEachIndexed
            val title = when (state) {
                "cancelled"     -> "${lesson.subjectName} fällt aus"
                "subjectchange" -> "Fachwechsel: ${lesson.subjectName} statt ${lesson.replacedSubject}"
                "subst"         -> "Vertretung: ${lesson.subjectName}"
                "roomchange"    -> "Raumänderung: ${lesson.subjectName}"
                else            -> lesson.subjectName
            }
            // The old text here was just the room, which on its own doesn't answer "which
            // class, on what date/period, and what exactly changed" — the actual questions a
            // parent has when this pops up. lesson.date/startTime/kl give the when/who; the
            // timegrid turns startTime into a period number ("3. Stunde", not just a raw time);
            // te/ro's orig* fields (populated by the v2 detail enrichment getSchoolDaysFrom
            // already does) give the specific before → after swap.
            val text = buildLessonChangeDetail(lesson, state, periodLookup)
            if (toNotify.size <= 4) {
                notificationHelper.notifyLessonChange(i, title, text)
            }
            notifiedKeys += "timetable:$key"
            log += ChangeLogEntry("timetable", title, text, now)
        }
        result = result.withNotified(notifiedKeys, log)
        return result
    }

    /** "Di, 16.09. · 3. Stunde (08:00–08:45) · Klasse 8c · Raum: A12 → B04" — everything needed
     *  to place a single changed lesson without having to go find it in the timetable to check
     *  which class/day/period it even was. */
    private fun buildLessonChangeDetail(lesson: Lesson, state: String, periodLookup: List<com.webuntis.dashboard.model.TimegridRow>): String {
        val parts = mutableListOf<String>()
        val period = periodLookup.periodNumberFor(lesson.startTime)
        val timeLabel = "${untisTimeLabel(lesson.startTime)}–${untisTimeLabel(lesson.endTime)}"
        parts += if (period != null) "${untisDateLabel(lesson.date)} · $period. Stunde ($timeLabel)"
                 else "${untisDateLabel(lesson.date)} · $timeLabel"
        lesson.displayClasses().takeIf { it.isNotBlank() }?.let { parts += "Klasse $it" }

        when (state) {
            "roomchange" -> roomChangeLabel(lesson)?.let { parts += "Raum: $it" }
                ?: lesson.displayRooms(false).takeIf { it.isNotBlank() }?.let { parts += "Raum $it" }
            "subst", "subjectchange" -> {
                teacherChangeLabel(lesson)?.let { parts += "Lehrer: $it" }
                lesson.displayRooms(false).takeIf { it.isNotBlank() }?.let { parts += "Raum $it" }
            }
            "cancelled" -> lesson.displayRooms(false).takeIf { it.isNotBlank() }?.let { parts += "Raum $it" }
        }
        return parts.joinToString(" · ")
    }

    /** "Vorher → nachher" for the teacher(s) on a lesson, from the per-position orig/current
     *  pair the v2 detail enrichment fills in (Lesson.te[].orgname vs .name) — falls back to
     *  just the current name(s) if there's nothing to compare against (e.g. enrichment budget
     *  ran out for this lesson, or it's a straightforward addition rather than a swap). */
    private fun teacherChangeLabel(lesson: Lesson): String? {
        val teachers = lesson.te?.takeIf { it.isNotEmpty() } ?: return null
        val from = teachers.mapNotNull { it.orgname?.takeIf { n -> n.isNotBlank() } }
            .distinct().joinToString(", ").takeIf { it.isNotBlank() }
        val to = teachers.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            .distinct().joinToString(", ").takeIf { it.isNotBlank() }
        return when {
            from != null && to != null && from != to -> "$from → $to"
            to != null -> to
            else -> from
        }
    }

    /** Same idea as [teacherChangeLabel] but for the room, from NamedItem.origName/origLongname
     *  vs name/longname. */
    private fun roomChangeLabel(lesson: Lesson): String? {
        val rooms = lesson.ro?.takeIf { it.isNotEmpty() } ?: return null
        val from = rooms.mapNotNull { it.origName?.takeIf { n -> n.isNotBlank() } }
            .distinct().joinToString(", ").takeIf { it.isNotBlank() }
        val to = lesson.displayRooms(false).takeIf { it.isNotBlank() }
        return when {
            from != null && to != null && from != to -> "$from → $to"
            to != null -> to
            else -> from
        }
    }

    private fun untisDateLabel(ymd: Int): String = try {
        val date = LocalDate.of(ymd / 10000, (ymd / 100) % 100, ymd % 100)
        date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, dd.MM.", java.util.Locale.GERMAN))
    } catch (e: Exception) { ymd.toString() }

    private fun untisTimeLabel(t: Int): String =
        t.toString().padStart(4, '0').let { "${it.take(2)}:${it.drop(2)}" }

    private suspend fun checkMessages(baseline: ChangeSnapshot, isFirstRun: Boolean): ChangeSnapshot {
        val fetched = repository.getMessages(forceRefresh = true)
        val messages = fetched.getOrNull()
        if (messages == null) {
            Log.w(tag, "checkMessages: fetch failed, keeping previous baseline — ${fetched.exceptionOrNull()}")
            return baseline
        }
        val currentIds = messages.map { it.id }.toSet()
        var result = baseline.copy(messageIds = currentIds)

        val candidates = if (isFirstRun) {
            // Don't silently swallow something that just arrived: on a first run the id-diff
            // has nothing to compare against, so fall back to the message's own date.
            messages.filter { isRecentIso(it.sentDateTime) }
        } else {
            messages.filter { it.id !in baseline.messageIds }
        }
        val newOnes = candidates.filter { baseline.isFresh("message:${it.id}") }
        Log.i(tag, "checkMessages: ${messages.size} fetched, ${candidates.size} candidate(s) (${if (isFirstRun) "first run: by date" else "by id-diff"}), ${newOnes.size} to notify")
        if (newOnes.isEmpty()) return result

        notificationHelper.notifyNewMessages(newOnes)
        val now = System.currentTimeMillis()
        result = result.withNotified(
            newOnes.map { "message:${it.id}" },
            newOnes.map {
                ChangeLogEntry(
                    "messages",
                    it.subject.orEmpty().ifBlank { applicationContext.getString(R.string.notif_message_no_subject) },
                    messageDetail(it),
                    now
                )
            }
        )
        return result
    }

    /** "Von Mustermann, Max · 30.09.2026, 14:05 Uhr" — sender and sent date/time for the log. */
    private fun messageDetail(message: Message): String {
        val sender = message.sender?.displayName?.takeIf { it.isNotBlank() }
            ?.let { applicationContext.getString(R.string.notif_message_from, it) }
        return listOfNotNull(sender, message.sentDateFormatted.takeIf { it.isNotBlank() })
            .joinToString(" · ")
    }

    private suspend fun checkHomework(baseline: ChangeSnapshot, isFirstRun: Boolean): ChangeSnapshot {
        val fetched = repository.getHomework(forceRefresh = true)
        val homework = fetched.getOrNull()?.first
        if (homework == null) {
            Log.w(tag, "checkHomework: fetch failed, keeping previous baseline — ${fetched.exceptionOrNull()}")
            return baseline
        }
        val currentIds = homework.map { it.id }.toSet()
        var result = baseline.copy(homeworkIds = currentIds)

        val candidates = if (isFirstRun) homework.filter { isRecentYmd(it.date) }
        else homework.filter { it.id !in baseline.homeworkIds }
        val newOnes = candidates.filter { baseline.isFresh("homework:${it.id}") }
        Log.i(tag, "checkHomework: ${homework.size} fetched, ${candidates.size} candidate(s) (${if (isFirstRun) "first run: by date" else "by id-diff"}), ${newOnes.size} to notify")
        if (newOnes.isEmpty()) return result

        notificationHelper.notifyNewHomework(newOnes.size, newOnes.singleOrNull()?.subject)
        val now = System.currentTimeMillis()
        result = result.withNotified(
            newOnes.map { "homework:${it.id}" },
            newOnes.map {
                // Previously just "" — with several homework entries in the dialog at once
                // (or a subject-less one falling back to "Neue Hausaufgabe"), there was no way
                // to tell which is which without opening each one individually.
                val dateLabel = it.date?.let { d -> untisDateLabel(d) }
                val dueLabel  = it.dueDate?.takeIf { due -> due != it.date }?.let { d -> "fällig ${untisDateLabel(d)}" }
                val text = listOfNotNull(dateLabel, dueLabel).joinToString(" · ")
                ChangeLogEntry("homework", it.subject.orEmpty().ifBlank { "Neue Hausaufgabe" }, text, now)
            }
        )
        return result
    }

    private suspend fun checkClassbook(baseline: ChangeSnapshot, isFirstRun: Boolean): ChangeSnapshot {
        val fetched = repository.getClassbookEntries(forceRefresh = true)
        val entries = fetched.getOrNull()
        if (entries == null) {
            Log.w(tag, "checkClassbook: fetch failed, keeping previous baseline — ${fetched.exceptionOrNull()}")
            return baseline
        }
        val currentIds = entries.map { it.id }.toSet()
        var result = baseline.copy(classbookIds = currentIds)

        val candidates = if (isFirstRun) entries.filter { isRecentYmd(it.date) }
        else entries.filter { it.id !in baseline.classbookIds }
        val newOnes = candidates.filter { baseline.isFresh("classbook:${it.id}") }
        Log.i(tag, "checkClassbook: ${entries.size} fetched, ${candidates.size} candidate(s) (${if (isFirstRun) "first run: by date" else "by id-diff"}), ${newOnes.size} to notify")
        if (newOnes.isEmpty()) return result

        notificationHelper.notifyNewClassbookEntries(newOnes.size, newOnes.singleOrNull()?.subject)
        val now = System.currentTimeMillis()
        result = result.withNotified(
            newOnes.map { "classbook:${it.id}" },
            newOnes.map {
                // Same gap as homework above: the date is the only thing that told two
                // same-subject entries apart, and it was never actually shown.
                val text = it.date?.let { d -> untisDateLabel(d) } ?: ""
                ChangeLogEntry("classbook", it.subject.orEmpty().ifBlank { "Neuer Klassenbucheintrag" }, text, now)
            }
        )
        return result
    }

    /** True for a "yyyy-MM-dd..." / ISO timestamp within the recency window (see [RECENT_WINDOW_DAYS]). */
    private fun isRecentIso(iso: String?): Boolean {
        val datePart = iso?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: return false
        return try {
            !LocalDate.parse(datePart).isBefore(LocalDate.now().minusDays(RECENT_WINDOW_DAYS))
        } catch (e: Exception) { false }
    }

    /** True for a WebUntis yyyyMMdd int within the recency window. */
    private fun isRecentYmd(ymd: Int?): Boolean {
        val v = ymd ?: return false
        return try {
            val date = LocalDate.of(v / 10000, (v / 100) % 100, v % 100)
            !date.isBefore(LocalDate.now().minusDays(RECENT_WINDOW_DAYS))
        } catch (e: Exception) { false }
    }

    companion object {
        /** How far back an item may be dated and still notify on a first run, where there's no
         *  previous snapshot to diff against. Kept short so a fresh install reports what just
         *  happened without replaying the whole term. */
        private const val RECENT_WINDOW_DAYS = 1L
    }
}
