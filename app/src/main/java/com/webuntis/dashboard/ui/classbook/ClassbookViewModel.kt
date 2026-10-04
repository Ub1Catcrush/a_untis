package com.webuntis.dashboard.ui.classbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webuntis.dashboard.api.WebUntisRepository
import com.webuntis.dashboard.model.ClassbookEntry
import com.webuntis.dashboard.model.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClassbookViewModel @Inject constructor(
    private val repository: WebUntisRepository,
    private val appForegroundEvents: com.webuntis.dashboard.api.AppForegroundEvents,
    val activeAccountManager: com.webuntis.dashboard.api.ActiveAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<ClassbookEntry>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ClassbookEntry>>> = _state

    private val _refreshing = MutableStateFlow(false)
    /** True only during a user-initiated pull-to-refresh (automatic reloads stay invisible). */
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _schoolYearLabel = MutableStateFlow<String?>(null)
    val schoolYearLabel: StateFlow<String?> = _schoolYearLabel

    private val _nameCatalog = MutableStateFlow(com.webuntis.dashboard.model.NameCatalog())
    /** Short↔long lookup so the subject/teacher can be shown per the per-screen name setting. */
    val nameCatalog: StateFlow<com.webuntis.dashboard.model.NameCatalog> = _nameCatalog

    val sessionManager: com.webuntis.dashboard.api.SessionManager get() = repository.sessionManager

    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            repository.peekNameCatalog()?.let { _nameCatalog.value = it }
            _nameCatalog.value = repository.getNameCatalog()
        }
        viewModelScope.launch {
            repository.getCurrentSchoolYearName().onSuccess { _schoolYearLabel.value = it }
        }
        viewModelScope.launch { appForegroundEvents.onForegroundResume.collect { load(forceRefresh = true) } }
        viewModelScope.launch { activeAccountManager.current.drop(1).collect { load(forceRefresh = true, contextChanged = true) } }
    }

    /**
     * Stale-while-revalidate. Whatever is already on screen stays; otherwise (first load, or
     * [contextChanged] = other account) the last known data FOR THE NEW CONTEXT is taken from
     * the cache (memory or disk) and shown at once — a spinner only appears if there is nothing
     * cached for it. The network refresh then runs silently behind. A failed refresh never
     * replaces content that is on screen. [userInitiated] = pull-to-refresh.
     */
    fun load(forceRefresh: Boolean = false, contextChanged: Boolean = false, userInitiated: Boolean = false) {
        if (contextChanged) loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (userInitiated) _refreshing.value = true
            try {
                if (contextChanged || _state.value !is UiState.Success) {
                    val cached = repository.peekClassbookEntries()
                    _state.value = if (cached != null) UiState.Success(cached) else UiState.Loading
                }
                repository.getClassbookEntries(forceRefresh).fold(
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
}
