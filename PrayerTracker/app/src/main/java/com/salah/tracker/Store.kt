package com.salah.tracker

import android.content.Context
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Madhab
import java.time.LocalDate

/** Tiny SharedPreferences wrapper: settings + which prayers you marked as prayed. */
class Store(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("salah", Context.MODE_PRIVATE)

    var location: Pair<Double, Double>?
        get() {
            val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
            val lng = prefs.getString("lng", null)?.toDoubleOrNull() ?: return null
            return lat to lng
        }
        set(v) {
            prefs.edit().putString("lat", v?.first?.toString()).putString("lng", v?.second?.toString()).apply()
        }

    var method: CalculationMethod
        get() = runCatching { CalculationMethod.valueOf(prefs.getString("method", null)!!) }
            .getOrDefault(CalculationMethod.MUSLIM_WORLD_LEAGUE)
        set(v) { prefs.edit().putString("method", v.name).apply() }

    var madhab: Madhab
        get() = runCatching { Madhab.valueOf(prefs.getString("madhab", null)!!) }
            .getOrDefault(Madhab.SHAFI)
        set(v) { prefs.edit().putString("madhab", v.name).apply() }

    var sound: String
        get() = prefs.getString("sound", null) ?: Sounds.DEFAULT_KEY
        set(v) { prefs.edit().putString("sound", v).apply() }

    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean("asked_notif", false)
        set(v) { prefs.edit().putBoolean("asked_notif", v).apply() }

    private fun key(date: LocalDate, p: Prayer) = "prayed_${date}_${p.name}"

    fun isPrayed(date: LocalDate, p: Prayer) = prefs.getBoolean(key(date, p), false)

    @android.annotation.SuppressLint("ApplySharedPref")
    fun setPrayed(date: LocalDate, p: Prayer, prayed: Boolean) {
        // commit() not apply(): this is often called from a BroadcastReceiver that may be killed right after.
        val e = prefs.edit().putBoolean(key(date, p), prayed)
        // Unchecking an old day means reminders must look back that far again.
        if (!prayed && scanFrom?.let { date < it } == true) e.putString("scan_from", date.toString())
        e.commit()
    }

    /**
     * Reminders only cover prayers from this moment on, so installing the app doesn't nag you
     * about years of prayers it never knew about. First run after the 1.3 update: starts from
     * the first day you checked anything, so prayers you already skipped are included.
     */
    val trackingSince: Long
        get() {
            if (prefs.contains("tracking_since")) return prefs.getLong("tracking_since", 0)
            val firstUsed = prefs.all.entries
                .filter { it.key.startsWith("prayed_") && it.value == true }
                .mapNotNull { runCatching { LocalDate.parse(it.key.substring(7, 17)) }.getOrNull() }
                .minOrNull()
            val v = firstUsed?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                ?: System.currentTimeMillis()
            prefs.edit().putLong("tracking_since", v).apply()
            return v
        }

    /** Every day before this is fully checked (an optimisation for [Scheduler.pending]). */
    var scanFrom: LocalDate?
        get() = prefs.getString("scan_from", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(v) { prefs.edit().putString("scan_from", v?.toString()).apply() }

    var nextNagAt: Long
        get() = prefs.getLong("next_nag_at", 0)
        set(v) { prefs.edit().putLong("next_nag_at", v).apply() }
}
