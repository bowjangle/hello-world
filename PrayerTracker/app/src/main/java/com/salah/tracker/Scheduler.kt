package com.salah.tracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Flow per prayer:
 *   1. At prayer time   → "time to pray" notification.
 *   2. 30 min later     → "Did you pray?" with [Yes] / [Not yet].
 *   3. Every 10 min     → asked again, even after the prayer's time is over, until you check it…
 *   4. …or until 11 pm  → one last reminder that says it's the last one. Then quiet until the
 *                         next day; unchecked prayers stay ✗ in the history.
 *
 * Steps 2–3 run as ONE repeating "nag" alarm for all prayers together, so several unchecked
 * prayers give one notification and one sound every 10 min, not one each.
 */
object Scheduler {
    const val ACTION_PRAYER_TIME = "com.salah.tracker.PRAYER_TIME"
    const val ACTION_NAG = "com.salah.tracker.NAG"
    const val ACTION_MARK_PRAYED = "com.salah.tracker.MARK_PRAYED"
    const val ACTION_NOT_YET = "com.salah.tracker.NOT_YET"
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_DATE = "date"

    const val FIRST_CHECK_DELAY_MS = 30 * 60_000L
    const val REPEAT_MS = 10 * 60_000L

    private const val RC_NEXT_PRAYER = 1
    private const val RC_NAG = 2

    /** Call on app open, boot, time/timezone change, settings change, and after marking. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        scheduleNextPrayerTime(ctx, now)
        scheduleNag(ctx, now, fromNag = false)
    }

    /** Reminders for a day stop here: 11 pm, or 30 min after Isha if Isha starts after 10:30 pm. */
    val CUTOFF: LocalTime = LocalTime.of(23, 0)

    fun cutoff(ctx: Context, date: LocalDate): Long? {
        val isha = PrayerTimesCalc.window(ctx, date, Prayer.ISHA) ?: return null
        val zone = ZoneId.systemDefault()
        val elevenPm = date.atTime(CUTOFF).atZone(zone).toInstant().toEpochMilli()
        val endOfDay = date.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        return minOf(maxOf(elevenPm, isha.start + FIRST_CHECK_DELAY_MS), endOfDay)
    }

    /** Today's last reminder has already gone out (or its time has passed). */
    fun pastCutoff(ctx: Context, now: Long): Boolean {
        val today = LocalDate.now()
        return Store(ctx).finalShownFor == today || now >= (cutoff(ctx, today) ?: return false)
    }

    /**
     * Today's prayers you haven't checked whose "did you pray?" time (prayer time + 30 min) has
     * passed, oldest first. Prayers from before you started using the app are ignored.
     * Earlier days are never included: at the cutoff they get one last reminder, then stop.
     */
    fun pending(ctx: Context, now: Long = System.currentTimeMillis()): List<PrayerWindow> {
        val store = Store(ctx)
        if (store.location == null) return emptyList()
        val since = store.trackingSince
        return (PrayerTimesCalc.windows(ctx, LocalDate.now()) ?: return emptyList()).filter {
            it.start >= since && !store.isPrayed(it.date, it.prayer) && now >= it.start + FIRST_CHECK_DELAY_MS
        }
    }


    /**
     * Arms the nag alarm. With something unchecked: every 10 min, and once more exactly at the
     * cutoff for the last reminder. With nothing unchecked (or after the cutoff): at the next
     * prayer's first check (prayer time + 30 min).
     */
    fun scheduleNag(ctx: Context, now: Long, fromNag: Boolean) {
        val store = Store(ctx)
        val cutoff = cutoff(ctx, LocalDate.now()) ?: return
        val at = if (!pastCutoff(ctx, now) && pending(ctx, now).isNotEmpty()) {
            val next = if (fromNag) now + REPEAT_MS
            // Keep an existing 10-minute rhythm; otherwise start within a minute.
            else store.nextNagAt.takeIf { it > now && it <= now + REPEAT_MS } ?: (now + 60_000L)
            minOf(next, cutoff)
        } else {
            nextFirstCheck(ctx, now) ?: return
        }
        store.nextNagAt = at
        setExact(ctx, at, PendingIntent.getBroadcast(
            ctx, RC_NAG, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_NAG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
    }

    /** The next "did you pray?" time that still falls before its day's cutoff. */
    private fun nextFirstCheck(ctx: Context, now: Long): Long? {
        val today = LocalDate.now()
        for (day in listOf(today, today.plusDays(1))) {
            val cut = cutoff(ctx, day) ?: return null
            if (day == today && Store(ctx).finalShownFor == today) continue
            val t = (PrayerTimesCalc.windows(ctx, day) ?: return null)
                .map { it.start + FIRST_CHECK_DELAY_MS }
                .firstOrNull { it > now && it <= cut }
            if (t != null) return t
        }
        return null
    }

    /** Schedules the single "next prayer time" alarm strictly after [after]. */
    fun scheduleNextPrayerTime(ctx: Context, after: Long) {
        val today = LocalDate.now()
        val next = listOf(today, today.plusDays(1))
            .flatMap { PrayerTimesCalc.windows(ctx, it) ?: return }
            .firstOrNull { it.start > after } ?: return
        setExact(ctx, next.start, pending(ctx, ACTION_PRAYER_TIME, RC_NEXT_PRAYER, next.prayer, next.date))
    }

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
