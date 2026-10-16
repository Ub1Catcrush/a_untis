package com.webuntis.dashboard.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Has the start-up update check finished?" — everything that logs in or leaves the login screen
 * waits for [isOpen] (see MainActivity.runUpdateGate and LoginViewModel's auto-login).
 *
 * The point: an update must be offered BEFORE the app logs in and opens the data screens. A
 * crash in those screens (a bug the update may well fix) would otherwise happen before the
 * update dialog ever shows, leaving no way to update from inside the app.
 *
 * Process-wide on purpose: it is checked once per app start, not on every rotation / activity
 * re-creation, and it is shared by the activity- and fragment-scoped LoginViewModel instances.
 * Opens immediately when no check is needed (F-Droid build, no connection).
 */
@Singleton
class UpdateGate @Inject constructor() {
    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen

    fun open() { _isOpen.value = true }
}
