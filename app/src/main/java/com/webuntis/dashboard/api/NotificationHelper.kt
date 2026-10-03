package com.webuntis.dashboard.api

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavDeepLinkBuilder
import com.webuntis.dashboard.MainActivity
import com.webuntis.dashboard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The independently switchable notification categories. Each one maps to its own Android
 *  notification channel (system settings) AND its own switch in the app's settings. */
enum class NotificationCategory { CANCELLATIONS, SUBSTITUTIONS, ROOM_CHANGES, MESSAGES, HOMEWORK, CLASSBOOK }

/**
 * Posts the local notifications shown by [PlanChangeCheckWorker] (cancellations,
 * substitutions, room changes, new messages, new homework, new classbook entries). One channel
 * per [NotificationCategory] so the user can mute/tune each kind individually in the system
 * settings; the app's own settings offer the same switches.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_CANCELLATIONS = "channel_timetable_cancellations"
        const val CHANNEL_SUBSTITUTIONS = "channel_timetable_substitutions"
        const val CHANNEL_ROOM_CHANGES  = "channel_timetable_room_changes"
        const val CHANNEL_MESSAGES      = "channel_new_messages"
        const val CHANNEL_HOMEWORK      = "channel_new_homework"
        const val CHANNEL_CLASSBOOK     = "channel_new_classbook"
        private const val GROUP_TIMETABLE = "group_timetable"

        /** Pre-split channel that held every timetable change; removed in [ensureChannels]. */
        private const val CHANNEL_TIMETABLE_LEGACY = "channel_timetable_changes"

        private const val ID_CANCELLATIONS = 10_000
        private const val ID_SUBSTITUTIONS = 11_000
        private const val ID_ROOM_CHANGES  = 12_000
        private const val ID_MESSAGES      = 20_000
        private const val ID_HOMEWORK      = 20_001
        private const val ID_CLASSBOOK     = 20_002
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(CHANNEL_TIMETABLE_LEGACY)
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_TIMETABLE, context.getString(R.string.notif_group_timetable))
        )
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_CANCELLATIONS,
                    context.getString(R.string.notif_channel_cancellations),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_cancellations_desc)
                    group = GROUP_TIMETABLE
                },
                NotificationChannel(
                    CHANNEL_SUBSTITUTIONS,
                    context.getString(R.string.notif_channel_substitutions),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_substitutions_desc)
                    group = GROUP_TIMETABLE
                },
                NotificationChannel(
                    CHANNEL_ROOM_CHANGES,
                    context.getString(R.string.notif_channel_room_changes),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_room_changes_desc)
                    group = GROUP_TIMETABLE
                },
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    context.getString(R.string.notif_channel_messages),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.notif_channel_messages_desc) },
                NotificationChannel(
                    CHANNEL_HOMEWORK,
                    context.getString(R.string.notif_channel_homework),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.notif_channel_homework_desc) },
                NotificationChannel(
                    CHANNEL_CLASSBOOK,
                    context.getString(R.string.notif_channel_classbook),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.notif_channel_classbook_desc) }
            )
        )
    }

    /** True once the user has actually granted the runtime permission (or it's not needed
     *  pre-Android 13) — callers should skip posting rather than crash if this is false. */
    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Opens the "Neuigkeiten" dialog (see RecentChangesDialogFragment) showing exactly what
     *  changed, rather than dropping the user on a fragment that only shows current state
     *  (e.g. the summary "45 Stunden geändert" notification didn't say which 45). Used as the
     *  tap target for all four change-check notification categories. */
    private fun recentChangesIntent(): PendingIntent =
        NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.recentChangesDialogFragment)
            .setComponentName(MainActivity::class.java)
            .createPendingIntent()

    private fun notify(id: Int, notification: android.app.Notification) {
        if (!hasPermission()) return
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and this call — nothing to do.
        }
    }

    private fun channelFor(category: NotificationCategory) = when (category) {
        NotificationCategory.CANCELLATIONS -> CHANNEL_CANCELLATIONS
        NotificationCategory.SUBSTITUTIONS -> CHANNEL_SUBSTITUTIONS
        NotificationCategory.ROOM_CHANGES  -> CHANNEL_ROOM_CHANGES
        else -> error("Not a timetable category: $category")
    }

    private fun idBaseFor(category: NotificationCategory) = when (category) {
        NotificationCategory.CANCELLATIONS -> ID_CANCELLATIONS
        NotificationCategory.SUBSTITUTIONS -> ID_SUBSTITUTIONS
        NotificationCategory.ROOM_CHANGES  -> ID_ROOM_CHANGES
        else -> error("Not a timetable category: $category")
    }

    /** One notification per changed lesson, posted on the channel of its [category]
     *  (cancellation / substitution / room change). */
    fun notifyLessonChange(category: NotificationCategory, offset: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, channelFor(category))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(recentChangesIntent())
            .build()
        // +1: the base id itself is reserved for the category's summary notification.
        notify(idBaseFor(category) + 1 + offset, notification)
    }

    /** Collapsed per-[category] summary used instead of per-item notifications when many
     *  lessons of that kind changed at once (e.g. a whole day reorganized) — avoids flooding
     *  the notification shade. */
    fun notifyLessonChangesSummary(category: NotificationCategory, count: Int) {
        val (titleRes, pluralRes) = when (category) {
            NotificationCategory.CANCELLATIONS -> R.string.notif_cancellations_summary_title to R.plurals.notif_cancellations_summary
            NotificationCategory.SUBSTITUTIONS -> R.string.notif_substitutions_summary_title to R.plurals.notif_substitutions_summary
            else                               -> R.string.notif_room_changes_summary_title  to R.plurals.notif_room_changes_summary
        }
        val notification = NotificationCompat.Builder(context, channelFor(category))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.resources.getQuantityString(pluralRes, count, count))
            .setAutoCancel(true)
            .setContentIntent(recentChangesIntent())
            .build()
        notify(idBaseFor(category), notification)
    }

    /**
     * New-message notification. Each message is described by sent date/time, sender and
     * subject: a single message gets a title of its subject plus "Von <Absender> · <Datum>",
     * several messages get a count summary and an expandable list with one line per message.
     */
    fun notifyNewMessages(messages: List<com.webuntis.dashboard.model.Message>) {
        if (messages.isEmpty()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(recentChangesIntent())

        if (messages.size == 1) {
            val m = messages.first()
            val subject = m.subject?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.notif_message_no_subject)
            val sender = m.sender?.displayName?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.notif_message_unknown_sender)
            val detail = listOf(
                context.getString(R.string.notif_message_from, sender),
                m.sentDateFormatted
            ).filter { it.isNotBlank() }.joinToString(" · ")
            builder.setContentTitle(context.getString(R.string.notif_messages_title))
                .setContentText(subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$subject\n$detail"))
        } else {
            val summary = context.resources.getQuantityString(
                R.plurals.notif_messages_summary, messages.size, messages.size
            )
            val inbox = NotificationCompat.InboxStyle().setSummaryText(summary)
            messages.take(5).forEach { m ->
                val subject = m.subject?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.notif_message_no_subject)
                val sender = m.sender?.displayName?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.notif_message_unknown_sender)
                inbox.addLine(
                    context.getString(R.string.notif_message_line, m.sentDateFormatted, sender, subject)
                )
            }
            builder.setContentTitle(context.getString(R.string.notif_messages_title))
                .setContentText(summary)
                .setStyle(inbox)
        }
        notify(ID_MESSAGES, builder.build())
    }

    fun notifyNewHomework(count: Int, singleSubject: String?) {
        val text = if (count == 1 && singleSubject != null) singleSubject
        else context.resources.getQuantityString(R.plurals.notif_homework_summary, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_HOMEWORK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_homework_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(recentChangesIntent())
            .build()
        notify(ID_HOMEWORK, notification)
    }

    fun notifyNewClassbookEntries(count: Int, singleSubject: String?) {
        val text = if (count == 1 && singleSubject != null) singleSubject
        else context.resources.getQuantityString(R.plurals.notif_classbook_summary, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_CLASSBOOK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_classbook_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(recentChangesIntent())
            .build()
        notify(ID_CLASSBOOK, notification)
    }
}