package com.webuntis.dashboard.ui.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.SchoolEvent
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repository: WebUntisRepository,
    private val appForegroundEvents: com.webuntis.dashboard.api.AppForegroundEvents,
    val activeAccountManager: com.webuntis.dashboard.api.ActiveAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<SchoolEvent>>>(UiState.Loading)
    val state: StateFlow<UiState<List<SchoolEvent>>> = _state

    private val _refreshing = MutableStateFlow(false)
    /** True only during a user-initiated pull-to-refresh (automatic reloads stay invisible). */
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _showPast = MutableStateFlow(false)
    val showPast: StateFlow<Boolean> = _showPast

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        load()
        viewModelScope.launch { appForegroundEvents.onForegroundResume.collect { load(forceRefresh = true) } }
        viewModelScope.launch { activeAccountManager.current.drop(1).collect { load(forceRefresh = true, contextChanged = true) } }
    }

    /**
     * Stale-while-revalidate. Whatever is already on screen stays; otherwise (first load, or
     * [contextChanged] = the data now belongs to something else, e.g. other account/tab/mode)
     * the last known data FOR THE NEW CONTEXT is taken from the cache (memory or disk) and shown
     * at once — a spinner only appears if there is nothing cached for it. The network refresh
     * then runs silently behind. A failed refresh never replaces content that is on screen.
     * [userInitiated] = pull-to-refresh, the only case that shows the swipe spinner.
     */

    fun load(forceRefresh: Boolean = false, contextChanged: Boolean = false, userInitiated: Boolean = false) {
        if (contextChanged) loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (userInitiated) _refreshing.value = true
            val past = _showPast.value
            try {
                if (contextChanged || _state.value !is UiState.Success) {
                    val cached = repository.peekEvents(includePast = past)
                    _state.value = if (cached != null) UiState.Success(cached) else UiState.Loading
                }
                repository.getEvents(forceRefresh, includePast = past).fold(
                    onSuccess = { _state.value = UiState.Success(it) },
                    onFailure = {
                        if (_state.value !is UiState.Success) _state.value = UiState.Error(it.message ?: "Fehler beim Laden")
                    }
                )
            } finally {
                if (userInitiated) _refreshing.value = false
            }
        }
    }

    fun setShowPast(past: Boolean) {
        if (_showPast.value == past) return
        _showPast.value = past
        load(forceRefresh = true, contextChanged = true)
    }

    fun toggleShowPast() = setShowPast(!_showPast.value)
}
