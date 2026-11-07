package com.salah.tracker

import android.content.Context
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.SunnahTimes
import com.batoulapps.adhan.data.DateComponents
import java.time.LocalDate

enum class Prayer(val label: String) {
    FAJR("Fajr"), DHUHR("Dhuhr"), ASR("Asr"), MAGHRIB("Maghrib"), ISHA("Isha")
}

/**
 * The span during which a prayer is "current".
 * [start] = prayer time. [end] = when we stop reminding you:
 *   Fajr → sunrise, Dhuhr → Asr, Asr → Maghrib, Maghrib → Isha,
 *   Isha → middle of the night (so it doesn't nag you every 10 minutes until dawn).
 */
data class PrayerWindow(val prayer: Prayer, val date: LocalDate, val start: Long, val end: Long)

object PrayerTimesCalc {

    /** Returns null if no location has been set yet. */
    fun windows(ctx: Context, date: LocalDate): List<PrayerWindow>? {
        val store = Store(ctx)
        val loc = store.location ?: return null
        val params = store.method.parameters.also { it.madhab = store.madhab }
        val pt = PrayerTimes(
            Coordinates(loc.first, loc.second),
            DateComponents(date.year, date.monthValue, date.dayOfMonth),
            params
        )
        val midnight = SunnahTimes(pt).middleOfTheNight
        fun w(p: Prayer, s: java.util.Date, e: java.util.Date) = PrayerWindow(p, date, s.time, e.time)
        return listOf(
            w(Prayer.FAJR, pt.fajr, pt.sunrise),
            w(Prayer.DHUHR, pt.dhuhr, pt.asr),
            w(Prayer.ASR, pt.asr, pt.maghrib),
            w(Prayer.MAGHRIB, pt.maghrib, pt.isha),
            w(Prayer.ISHA, pt.isha, midnight),
        )
    }

    fun window(ctx: Context, date: LocalDate, prayer: Prayer): PrayerWindow? =
        windows(ctx, date)?.firstOrNull { it.prayer == prayer }
}
