package com.salah.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val now = System.currentTimeMillis()

        when (intent.action) {
            Scheduler.ACTION_NAG -> {
                Notifications.showNag(ctx, Scheduler.pending(ctx, now), alert = true)
                Scheduler.scheduleNag(ctx, now, fromNag = true)
                return
            }
            // The nag repeats on its own in 10 minutes; just dismiss it for now.
            Scheduler.ACTION_NOT_YET -> {
                Notifications.cancelNag(ctx)
                return
            }
        }

        val prayer = intent.getStringExtra(Scheduler.EXTRA_PRAYER)
            ?.let { runCatching { Prayer.valueOf(it) }.getOrNull() } ?: return
        val date = intent.getStringExtra(Scheduler.EXTRA_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val store = Store(ctx)

        when (intent.action) {
            Scheduler.ACTION_PRAYER_TIME -> {
                val w = PrayerTimesCalc.window(ctx, date, prayer)
                // Always chain the next prayer first so the cycle never breaks.
                Scheduler.scheduleNextPrayerTime(ctx, maxOf(now, w?.start ?: now))
                if (w != null && !store.isPrayed(date, prayer)) Notifications.showPrayerTime(ctx, w)
                Scheduler.scheduleNag(ctx, now, fromNag = false)
            }

            Scheduler.ACTION_MARK_PRAYED -> {
                store.setPrayed(date, prayer, true)
                Notifications.cancel(ctx, prayer)
                // Show the next unchecked prayer (quietly), or clear the reminder if none are left.
                Notifications.showNag(ctx, Scheduler.pending(ctx, now), alert = false)
                Scheduler.scheduleNag(ctx, now, fromNag = false)
            }
        }
    }
}
