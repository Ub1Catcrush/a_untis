package com.webuntis.dashboard.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fires the fixed fixed-time check ([NotificationScheduler.ACTION_FIXED_CHECK]) and keeps
 * the alarm chain alive: an alarm is one-shot, so every trigger immediately arms the next one.
 * Alarms don't survive a reboot, an app update, or a clock/time-zone change, and exact-alarm
 * permission can be granted/revoked at any time — all of those re-arm it here as well.
 */
@AndroidEntryPoint
class FixedCheckAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var sessionManager: SessionManager

    override fun onReceive(context: Context, intent: Intent) {
        val slots = sessionManager.activeFixedCheckSlots
        if (slots.isEmpty()) {
            NotificationScheduler.cancelFixedSlot(context)
            return
        }
        // Re-arm first so the next slot is safe even if the check below fails.
        NotificationScheduler.scheduleFixedSlot(context, slots)
        if (intent.action == NotificationScheduler.ACTION_FIXED_CHECK) {
            NotificationScheduler.runFixedCheck(context)
        }
    }
}
