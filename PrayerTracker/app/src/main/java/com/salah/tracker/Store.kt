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
        prefs.edit().putBoolean(key(date, p), prayed).commit()
    }
}
