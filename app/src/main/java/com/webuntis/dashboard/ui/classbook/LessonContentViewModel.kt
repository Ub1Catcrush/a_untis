package com.webuntis.dashboard.ui.classbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.SessionManager
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.Lesson
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/**
 * One section's "Unterrichtsinhalt" entries — i.e. the per-lesson Content field shown in the
 * timetable (Lesson.teachingContent) — grouped either by subject or by day depending on
 * [LessonContentViewModel.groupMode]. [header] is the subject name or the formatted day,
 * respectively.
 */
data class ContentGroup(val header: String, val entries: List<Lesson>)

@HiltViewModel
class LessonContentViewModel @Inject constructor(
    private val repository: WebUntisRepository,
    private val appForegroundEvents: com.webuntis.dashboard.api.AppForegroundEvents,
    val activeAccountManager: com.webuntis.dashboard.api.ActiveAccountManager
) : ViewModel() {

    companion object {
        /** How many more days "Weitere Tage laden" adds on each tap. */
        const val LOAD_MORE_INCREMENT = 14
        /** Safety cap on how far "Weitere Tage laden" can widen the window, to bound the number
         *  of per-lesson detail calls a single tab can trigger. */
        const val MAX_WINDOW_DAYS = 180
    }

    private val _state = MutableStateFlow<UiState<List<ContentGroup>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ContentGroup>>> = _state

    private val _windowDays = MutableStateFlow(repository.sessionManager.lessonContentDefaultDays.coerceAtLeast(1))
    val windowDays: StateFlow<Int> = _windowDays

    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore

    private val _groupMode = MutableStateFlow(repository.sessionManager.lessonContentGroupMode)
    val groupMode: StateFlow<SessionManager.LessonContentGroupMode> = _groupMode

    /** Entry count from the previous fetch, to detect when widening the window stopped turning
     *  up anything new (e.g. the start of the school year was reached). */
    private var lastEntryCount = -1

    /** The lessons from the most recent fetch, kept around so switching group mode is a pure
     *  re-grouping of already-loaded data — no network call needed. */
    private var lastLessons: List<Lesson> = emptyList()

    private val _refreshing = MutableStateFlow(false)
    /** True only during a user-initiated pull-to-refresh (automatic reloads stay invisible). */
    val refreshing: StateFlow<Boolean> = _refreshing

    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch { appForegroundEvents.onForegroundResume.collect { load() } }
        viewModelScope.launch { activeAccountManager.current.drop(1).collect { load(contextChanged = true) } }
    }

    /**
     * Stale-while-revalidate, same as the other tabs: last known content (memory or disk) is
     * shown at once — also right after an account switch ([contextChanged]) if that account has
     * a cache — and the refresh runs silently behind it. [forceRefresh] (pull-to-refresh) drops
     * the cache and reloads the whole window; a plain [load] (start / app resume) only
     * refreshes the most recent days once the cache is older than the cache TTL.
     */
    fun load(forceRefresh: Boolean = false, contextChanged: Boolean = false, userInitiated: Boolean = false) {
        if (contextChanged) loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (userInitiated) _refreshing.value = true
            try {
                if (contextChanged || _state.value !is UiState.Success) {
                    if (contextChanged) { lastEntryCount = -1; lastLessons = emptyList(); _canLoadMore.value = true }
                    val cached = repository.peekTeachingContentEntries(_windowDays.value)
                    if (cached != null) {
                        // Deliberately leaves lastEntryCount/canLoadMore alone: a cached list
                        // says nothing about whether widening the window finds more.
                        lastLessons = cached
                        _state.value = UiState.Success(group(cached, _groupMode.value))
                    } else _state.value = UiState.Loading
                }
                fetchAndApply(_windowDays.value, forceRefresh)
            } finally {
                if (userInitiated) _refreshing.value = false
            }
        }
    }

    /** Widens the visible window by [LOAD_MORE_INCREMENT] more days and re-fetches — only the
     *  newly-uncovered (older) slice actually hits the network, see
     *  WebUntisRepository.getTeachingContentEntries(). */
    val sessionManager: SessionManager get() = repository.sessionManager

    fun loadMoreDays() {
        if (_windowDays.value >= MAX_WINDOW_DAYS) return
        viewModelScope.launch {
            _windowDays.value = (_windowDays.value + LOAD_MORE_INCREMENT).coerceAtMost(MAX_WINDOW_DAYS)
            fetchAndApply(_windowDays.value, forceRefresh = false)
        }
    }

    /** Switches between grouping by subject and by day, persists the choice as the new default,
     *  and immediately re-renders from the already-loaded lessons (no re-fetch). */
    fun toggleGroupMode() {
        val next = if (_groupMode.value == SessionManager.LessonContentGroupMode.BY_DAY)
            SessionManager.LessonContentGroupMode.BY_SUBJECT
        else
            SessionManager.LessonContentGroupMode.BY_DAY
        repository.sessionManager.lessonContentGroupMode = next
        _groupMode.value = next
        if (lastLessons.isNotEmpty() || _state.value is UiState.Success) {
            _state.value = UiState.Success(group(lastLessons, next))
        }
    }

    private suspend fun fetchAndApply(days: Int, forceRefresh: Boolean) {
        repository.getTeachingContentEntries(days, forceRefresh).fold(
            onSuccess = { lessons -> applyResult(lessons, days) },
            onFailure = {
                if (_state.value !is UiState.Success) _state.value = UiState.Error(it.message ?: "Fehler beim Laden")
            }
        )
    }

    private fun applyResult(lessons: List<Lesson>, days: Int) {
        _canLoadMore.value = days < MAX_WINDOW_DAYS &&
            (lastEntryCount == -1 || lessons.size > lastEntryCount)
        lastEntryCount = lessons.size
        lastLessons = lessons

        _state.value = UiState.Success(group(lessons, _groupMode.value))
    }

    private fun group(lessons: List<Lesson>, mode: SessionManager.LessonContentGroupMode): List<ContentGroup> {
        val subjectStyle = repository.sessionManager.nameStyle(
            com.webuntis.dashboard.model.NameScreen.LESSON_CONTENT, com.webuntis.dashboard.model.NameType.SUBJECT)
        return if (mode == SessionManager.LessonContentGroupMode.BY_SUBJECT) {
            lessons
                .groupBy { lesson -> lesson.displaySubject(subjectStyle.long, subjectStyle.shortInParens) }
                .toSortedMap(compareBy { it.lowercase() })
                .map { (subject, entries) -> ContentGroup(subject, entries.sortedByDescending { it.date }) }
        } else {
            lessons
                .groupBy { it.date }
                .toSortedMap(compareByDescending { it })
                .map { (date, entries) ->
                    val local = entries.firstOrNull()?.localDate
                    val weekday = local?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.GERMAN)
                    val formatted = entries.firstOrNull()?.dateFormatted ?: date.toString()
                    val header = if (weekday != null) "$weekday, $formatted" else formatted
                    ContentGroup(header, entries.sortedBy { it.startTime })
                }
        }
    }
}
