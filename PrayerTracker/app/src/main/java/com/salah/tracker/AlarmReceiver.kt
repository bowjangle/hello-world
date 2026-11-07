package com.salah.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prayer = intent.getStringExtra(Scheduler.EXTRA_PRAYER)
            ?.let { runCatching { Prayer.valueOf(it) }.getOrNull() } ?: return
        val date = intent.getStringExtra(Scheduler.EXTRA_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val store = Store(ctx)
        val now = System.currentTimeMillis()

        when (intent.action) {
            Scheduler.ACTION_PRAYER_TIME -> {
                val w = PrayerTimesCalc.window(ctx, date, prayer)
                // Always chain the next prayer first so the cycle never breaks.
                Scheduler.scheduleNextPrayerTime(ctx, maxOf(now, w?.start ?: now))
                if (w == null || store.isPrayed(date, prayer)) return
                Notifications.clearAll(ctx) // drop any stale "did you pray X?" from the previous prayer
                Notifications.showPrayerTime(ctx, w)
                val firstCheck = w.start + Scheduler.FIRST_CHECK_DELAY_MS
                if (firstCheck < w.end) Scheduler.scheduleCheck(ctx, w, firstCheck)
            }

            Scheduler.ACTION_CHECK -> {
                val w = PrayerTimesCalc.window(ctx, date, prayer) ?: return
                if (store.isPrayed(date, prayer) || now >= w.end) {
                    Notifications.cancel(ctx, prayer)
                    return
                }
                Notifications.showCheck(ctx, w)
                val next = now + Scheduler.REPEAT_MS
                if (next < w.end) Scheduler.scheduleCheck(ctx, w, next)
            }

            Scheduler.ACTION_MARK_PRAYED -> {
                store.setPrayed(date, prayer, true)
                Scheduler.cancelCheck(ctx, prayer)
                Notifications.cancel(ctx, prayer)
            }

            // The next 10-minute reminder is already scheduled; just dismiss this one.
            Scheduler.ACTION_NOT_YET -> Notifications.cancel(ctx, prayer)
        }
    }
}
