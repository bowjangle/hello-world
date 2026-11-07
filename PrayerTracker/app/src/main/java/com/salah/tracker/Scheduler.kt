package com.salah.tracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate

/**
 * Flow per prayer:
 *   1. At prayer time        → gentle "time to pray" notification.
 *   2. 30 min later          → "Did you pray?" with [Yes] / [Not yet].
 *   3. Every 10 min after    → same question, until you tap Yes or the prayer's window ends.
 */
object Scheduler {
    const val ACTION_PRAYER_TIME = "com.salah.tracker.PRAYER_TIME"
    const val ACTION_CHECK = "com.salah.tracker.CHECK"
    const val ACTION_MARK_PRAYED = "com.salah.tracker.MARK_PRAYED"
    const val ACTION_NOT_YET = "com.salah.tracker.NOT_YET"
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_DATE = "date"

    const val FIRST_CHECK_DELAY_MS = 30 * 60_000L
    const val REPEAT_MS = 10 * 60_000L

    private const val RC_NEXT_PRAYER = 1
    private fun rcCheck(p: Prayer) = 100 + p.ordinal

    /** Call on app open, boot, time/timezone change, and settings change. Safe to call repeatedly. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        scheduleNextPrayerTime(ctx, now)

        // Restore reminders for any prayer whose window is open right now and isn't marked yet
        // (e.g. phone rebooted mid-window). Don't disturb a reminder that's already pending.
        val store = Store(ctx)
        val today = LocalDate.now()
        for (date in listOf(today.minusDays(1), today)) {
            for (w in PrayerTimesCalc.windows(ctx, date) ?: return) {
                if (now < w.start || now >= w.end) continue
                if (store.isPrayed(w.date, w.prayer)) { cancelCheck(ctx, w.prayer); continue }
                if (checkPending(ctx, w.prayer)) continue
                val at = maxOf(w.start + FIRST_CHECK_DELAY_MS, now + 60_000L)
                if (at < w.end) scheduleCheck(ctx, w, at)
            }
        }
    }

    /** Schedules the single "next prayer time" alarm strictly after [after]. */
    fun scheduleNextPrayerTime(ctx: Context, after: Long) {
        val today = LocalDate.now()
        val next = listOf(today, today.plusDays(1))
            .flatMap { PrayerTimesCalc.windows(ctx, it) ?: return }
            .firstOrNull { it.start > after } ?: return
        setExact(ctx, next.start, pending(ctx, ACTION_PRAYER_TIME, RC_NEXT_PRAYER, next.prayer, next.date))
    }

    fun scheduleCheck(ctx: Context, w: PrayerWindow, at: Long) {
        setExact(ctx, at, pending(ctx, ACTION_CHECK, rcCheck(w.prayer), w.prayer, w.date))
    }

    fun cancelCheck(ctx: Context, p: Prayer) {
        val pi = PendingIntent.getBroadcast(
            ctx, rcCheck(p), Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    private fun checkPending(ctx: Context, p: Prayer): Boolean = PendingIntent.getBroadcast(
        ctx, rcCheck(p), Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    ) != null

    fun intent(ctx: Context, action: String, p: Prayer, date: LocalDate): Intent =
        Intent(ctx, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_PRAYER, p.name)
            .putExtra(EXTRA_DATE, date.toString())

    fun pending(ctx: Context, action: String, rc: Int, p: Prayer, date: LocalDate): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, rc, intent(ctx, action, p, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setExact(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Fallback: may fire a few minutes late, but still fires.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
