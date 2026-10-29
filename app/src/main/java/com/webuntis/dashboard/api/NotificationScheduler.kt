package com.webuntis.dashboard.api

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Enqueues/cancels the periodic [PlanChangeCheckWorker] job. 15 minutes is Android's hard
 * floor for periodic WorkManager work — anything shorter is silently clamped to 15 min by the
 * OS anyway, so that's the shortest interval actually achievable here. Actual execution timing
 * beyond that is up to the OS (Doze/App Standby), not something the app controls directly.
 */
object NotificationScheduler {
    private const val UNIQUE_WORK_NAME = "plan_change_check"
    const val IMMEDIATE_CHECK_WORK_NAME = "plan_change_check_immediate"

    /** Legacy WorkManager-based slot chain from an earlier build — only cancelled now. */
    private const val LEGACY_FIXED_WORK_NAME = "plan_change_check_fixed"
    private const val FIXED_RUN_WORK_NAME = "plan_change_check_fixed_run"
    private const val ALARM_REQUEST_CODE = 6180
    const val ACTION_FIXED_CHECK = "com.webuntis.dashboard.action.FIXED_CHECK"

    /** [fixedSlots]: minutes since midnight for the fixed-time check; empty = none. */
    fun start(context: Context, fixedSlots: Set<Int>) {
        scheduleFixedSlot(context, fixedSlots)
        val request = PeriodicWorkRequestBuilder<PlanChangeCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        cancelFixedSlot(context)
    }

    /** Milliseconds from [now] to the next time in [slots] (minutes since midnight, local time). [slots] must not be empty. */
    internal fun delayToNextFixedSlotMs(slots: Set<Int>, now: ZonedDateTime = ZonedDateTime.now()): Long {
        val next = (0..1).flatMap { dayOffset ->
            slots.sorted().map { m -> now.toLocalDate().plusDays(dayOffset.toLong()).atTime(LocalTime.of(m / 60, m % 60)).atZone(now.zone) }
        }.first { it.isAfter(now.plusSeconds(30)) }
        return Duration.between(now, next).toMillis()
    }

    /** True if exact alarms may be scheduled (always true below Android 12). */
    fun canScheduleExactAlarms(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** System screen where the user can allow exact alarms for this app; null if not needed. */
    fun exactAlarmPermissionIntent(context: Context): Intent? {
        if (canScheduleExactAlarms(context)) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")
        )
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, ALARM_REQUEST_CODE,
        Intent(context, FixedCheckAlarmReceiver::class.java).setAction(ACTION_FIXED_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Arms ONE alarm for the next of the chosen [slots] (local time, any number of times per day
     * on a 15-minute grid; only the next one is armed at a time). An empty set cancels the alarm. [FixedCheckAlarmReceiver] re-arms
     * the following slot each time it fires and after boot / time or time-zone change / app
     * update. Uses an exact, Doze-proof alarm when the user allowed it; otherwise falls back to
     * an inexact (but still Doze-allowed) alarm so the check still happens, just not to the minute.
     * Idempotent: re-arming replaces the pending alarm.
     */
    fun scheduleFixedSlot(context: Context, slots: Set<Int>) {
        if (slots.isEmpty()) { cancelFixedSlot(context); return }
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_FIXED_WORK_NAME)
        val am = context.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + delayToNextFixedSlotMs(slots)
        val pi = alarmPendingIntent(context)
        try {
            if (canScheduleExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancelFixedSlot(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmPendingIntent(context))
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_FIXED_WORK_NAME)
    }

    /** Called by the alarm: runs the normal check once, right away. */
    fun runFixedCheck(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<PlanChangeCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FIXED_RUN_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Runs the check exactly once, immediately, outside the periodic schedule — used by the
     * "Jetzt prüfen" button in Settings so a user (or a bug report) can verify the whole
     * pipeline works right now instead of having to wait up to 15 minutes and having no way to
     * tell whether a lack of notification means "nothing changed" or "the pipeline is broken".
     * Returns the enqueued request's ID so the caller can observe its [androidx.work.WorkInfo].
     */
    fun checkNow(context: Context): java.util.UUID {
        val request = androidx.work.OneTimeWorkRequestBuilder<PlanChangeCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_CHECK_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }
}
