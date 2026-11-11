package com.salah.tracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Madhab
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var root: LinearLayout

    // Fields of the open settings dialog, so a location fix can fill them in.
    private var latField: EditText? = null
    private var lngField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Notifications.ensureChannel(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33 && !store.askedNotificationPermission &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            store.askedNotificationPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIF)
        }
        if (store.location == null) showSettings()
    }

    override fun onResume() {
        super.onResume()
        Scheduler.rescheduleAll(this)
        render()
    }

    // ---------- UI ----------

    private fun render() {
        root.removeAllViews()
        val today = LocalDate.now()

        root.addView(text(getString(R.string.app_name), 26f, bold = true))
        root.addView(text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")), 15f, dim = true))

        warnings()

        val windows = PrayerTimesCalc.windows(this, today)
        if (windows == null) {
            root.addView(text("Set your location so prayer times can be calculated.", 16f).apply { pad(top = 24) })
            root.addView(button("Set location") { showSettings() })
            return
        }

        root.addView(section("Today"))
        val now = System.currentTimeMillis()
        for (w in windows) root.addView(prayerRow(w, now))

        root.addView(section("Last 7 days"))
        root.addView(text("Tap a day to check or uncheck its prayers.", 12f, dim = true))
        for (i in 1..7) root.addView(historyRow(today.minusDays(i.toLong())))

        val loc = store.location!!
        root.addView(
            text(
                "Location %.4f, %.4f · %s · Asr: %s".format(
                    loc.first, loc.second, methodName(store.method),
                    if (store.madhab == Madhab.HANAFI) "Hanafi" else "Standard"
                ), 12f, dim = true
            ).apply { pad(top = 24) }
        )
        root.addView(button("Settings") { showSettings() })
        root.addView(button("Reminder sound: ${Sounds.label(this, store.sound)}") { showSoundPicker() })
        root.addView(button("Test notification") {
            Notifications.showTest(this)
            val problems = Notifications.soundProblems(this)
            val enabled = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
            toast(
                when {
                    !enabled -> "Notifications are off for this app — turn them on first."
                    problems.isNotEmpty() -> problems.first()
                    store.sound == Sounds.SILENT -> "Sent (silently — you chose no sound)."
                    else -> "Sent — you should hear it now."
                }
            )
        })
    }

    private fun prayerRow(w: PrayerWindow, now: Long): View {
        val prayed = store.isPrayed(w.date, w.prayer)
        val status = when {
            prayed -> "prayed"
            now < w.start -> "upcoming"
            now < w.end -> "now"
            else -> "not checked"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            pad(top = 6, bottom = 6)
        }
        val cb = CheckBox(this).apply {
            text = w.prayer.label
            textSize = 18f
            isChecked = prayed
            // Can't have prayed before the time has come in.
            isEnabled = prayed || now >= w.start
            setOnCheckedChangeListener { _, checked ->
                markChanged(w.date, w.prayer, checked)
            }
        }
        row.addView(cb, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(text(status, 13f, dim = true).apply { pad(right = 12) })
        row.addView(text(DateFormat.getTimeFormat(this).format(Date(w.start)), 16f))
        return row
    }

    /** Single place that reacts to checking/unchecking a prayer, from any screen. */
    private fun markChanged(date: LocalDate, p: Prayer, checked: Boolean) {
        store.setPrayed(date, p, checked)
        if (checked) Notifications.cancel(this, p)
        Scheduler.rescheduleAll(this)
        Notifications.showNag(this, Scheduler.pending(this), alert = false)
        render()
    }

    /** Tap a past day to check/uncheck its prayers. */
    private fun editDay(date: LocalDate) {
        val prayers = Prayer.entries
        val checked = prayers.map { store.isPrayed(date, it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")))
            .setMultiChoiceItems(prayers.map { it.label }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Save") { _, _ ->
                prayers.forEachIndexed { i, p ->
                    if (store.isPrayed(date, p) != checked[i]) markChanged(date, p, checked[i])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun historyRow(date: LocalDate): View {
        val marks = Prayer.entries.map { store.isPrayed(date, it) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            pad(top = 8, bottom = 8)
            isClickable = true
            setOnClickListener { editDay(date) }
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        row.addView(
            text(date.format(DateTimeFormatter.ofPattern("EEE d MMM")), 15f),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        row.addView(text(marks.joinToString(" ") { if (it) "✓" else "✗" }, 15f).apply { pad(right = 12) })
        row.addView(text("${marks.count { it }}/5", 15f, bold = true))
        return row
    }

    private fun warnings() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            root.addView(text("⚠ Notifications are off — you won't get reminders.", 14f).apply { pad(top = 16) })
            root.addView(button("Allow notifications") {
                if (Build.VERSION.SDK_INT >= 33 && !store.askedNotificationPermission) {
                    store.askedNotificationPermission = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIF)
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            })
        } else {
            val soundProblems = Notifications.soundProblems(this)
            for (p in soundProblems) root.addView(text("🔇 $p", 14f).apply { pad(top = 16) })
            if (soundProblems.isNotEmpty()) root.addView(button("Open sound settings for reminders") {
                startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.channel(this))
                )
            })
        }
        if (!Scheduler.canScheduleExact(this)) {
            root.addView(text("⚠ Exact alarms not allowed — reminders may be a few minutes late.", 14f).apply { pad(top = 16) })
            if (Build.VERSION.SDK_INT >= 31) root.addView(button("Allow exact alarms") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }
    }

    // ---------- Sound picker ----------

    private fun showSoundPicker() {
        // Rows: built-in sounds, then phone default, a sound from the phone, silent.
        val keys = Sounds.BUILT_IN.map { it.key } + listOf(Sounds.PHONE_DEFAULT, PICK_FROM_PHONE, Sounds.SILENT)
        val current = store.sound
        val labels = Sounds.BUILT_IN.map { it.label } + listOf(
            "Phone's default sound",
            if (current.startsWith("uri:")) Sounds.label(this, current) + "  (change…)" else "Choose from phone sounds…",
            "Silent (no sound)",
        )
        var picked = when {
            current.startsWith("uri:") -> keys.indexOf(PICK_FROM_PHONE)
            else -> keys.indexOf(current).takeIf { it >= 0 } ?: keys.indexOf(Sounds.DEFAULT_KEY)
        }
        AlertDialog.Builder(this)
            .setTitle("Reminder sound")
            .setSingleChoiceItems(labels.toTypedArray(), picked) { dialog, which ->
                if (keys[which] == PICK_FROM_PHONE) {
                    dialog.dismiss()
                    openPhoneSoundPicker()
                } else {
                    picked = which
                    Sounds.preview(this, keys[which]) // tap = listen
                }
            }
            .setPositiveButton("Use this") { _, _ ->
                if (keys[picked] != PICK_FROM_PHONE) saveSound(keys[picked])
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { Sounds.stopPreview() }
            .show()
    }

    private fun openPhoneSoundPicker() {
        val existing = store.sound.takeIf { it.startsWith("uri:") }?.let { Sounds.uriFor(this, it) }
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Reminder sound")
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, RC_RINGTONE)
        } catch (e: android.content.ActivityNotFoundException) {
            toast("This phone has no sound picker.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_RINGTONE || resultCode != RESULT_OK) return
        val uri: Uri = (if (Build.VERSION.SDK_INT >= 33)
            data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        else @Suppress("DEPRECATION") data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI))
            ?: return
        saveSound(Sounds.uriKey(uri))
    }

    private fun saveSound(key: String) {
        store.sound = key
        Notifications.ensureChannel(this) // new channel with the new sound; old one removed
        toast("Reminder sound: ${Sounds.label(this, key)}")
        render()
    }

    // ---------- Settings ----------

    private val methods = listOf(
        CalculationMethod.MUSLIM_WORLD_LEAGUE, CalculationMethod.NORTH_AMERICA, CalculationMethod.EGYPTIAN,
        CalculationMethod.KARACHI, CalculationMethod.UMM_AL_QURA, CalculationMethod.DUBAI,
        CalculationMethod.KUWAIT, CalculationMethod.QATAR, CalculationMethod.SINGAPORE,
        CalculationMethod.MOON_SIGHTING_COMMITTEE,
    )

    private fun methodName(m: CalculationMethod) = when (m) {
        CalculationMethod.MUSLIM_WORLD_LEAGUE -> "Muslim World League"
        CalculationMethod.NORTH_AMERICA -> "ISNA (North America)"
        CalculationMethod.EGYPTIAN -> "Egyptian"
        CalculationMethod.KARACHI -> "Karachi"
        CalculationMethod.UMM_AL_QURA -> "Umm al-Qura (Makkah)"
        CalculationMethod.DUBAI -> "Dubai"
        CalculationMethod.KUWAIT -> "Kuwait"
        CalculationMethod.QATAR -> "Qatar"
        CalculationMethod.SINGAPORE -> "Singapore"
        CalculationMethod.MOON_SIGHTING_COMMITTEE -> "Moonsighting Committee"
        else -> m.name
    }

    private fun showSettings() {
        val loc = store.location
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val numeric = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val lat = EditText(this).apply { hint = "Latitude (e.g. 21.4225)"; inputType = numeric; setText(loc?.first?.toString() ?: "") }
        val lng = EditText(this).apply { hint = "Longitude (e.g. 39.8262)"; inputType = numeric; setText(loc?.second?.toString() ?: "") }
        latField = lat; lngField = lng

        box.addView(button("Use my current location") { requestLocation() })
        box.addView(lat); box.addView(lng)

        box.addView(text("Calculation method", 14f, dim = true).apply { pad(top = 12) })
        val methodSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, methods.map(::methodName))
            setSelection(methods.indexOf(store.method).coerceAtLeast(0))
        }
        box.addView(methodSpin)

        box.addView(text("Asr time", 14f, dim = true).apply { pad(top = 12) })
        val madhabSpin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Standard (Shafi'i, Maliki, Hanbali)", "Hanafi (later Asr)")
            )
            setSelection(if (store.madhab == Madhab.HANAFI) 1 else 0)
        }
        box.addView(madhabSpin)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val la = lat.text.toString().trim().toDoubleOrNull()
                val ln = lng.text.toString().trim().toDoubleOrNull()
                if (la == null || ln == null || la !in -90.0..90.0 || ln !in -180.0..180.0) {
                    toast("Invalid latitude/longitude — not saved.")
                } else {
                    store.location = la to ln
                    store.method = methods[methodSpin.selectedItemPosition]
                    store.madhab = if (madhabSpin.selectedItemPosition == 1) Madhab.HANAFI else Madhab.SHAFI
                    // Times changed: drop the old "next prayer" alarm by re-arming from scratch.
                    Scheduler.rescheduleAll(this)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { latField = null; lngField = null }
            .show()
    }

    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), RC_LOCATION)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        try {
            val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null) { fillLocation(last); return }
            val provider = when {
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                else -> { toast("Location is turned off. Turn it on or type coordinates."); return }
            }
            toast("Getting location…")
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { l ->
                    if (l != null) fillLocation(l) else toast("Couldn't get a location fix. Type coordinates instead.")
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { l -> fillLocation(l) }, mainLooper)
            }
        } catch (e: SecurityException) {
            toast("Location permission denied.")
        }
    }

    private fun fillLocation(l: Location) {
        // Rounded to ~1 km: plenty for prayer times, and less precise data stored.
        val la = "%.4f".format(java.util.Locale.US, l.latitude)
        val ln = "%.4f".format(java.util.Locale.US, l.longitude)
        if (latField != null) {
            latField?.setText(la); lngField?.setText(ln)
            toast("Location filled in — tap Save.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            RC_LOCATION -> if (granted) requestLocation() else toast("Without location permission, type coordinates manually.")
            RC_NOTIF -> render()
        }
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun View.pad(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        setPadding(dp(left), dp(top), dp(right), dp(bottom))

    private fun text(s: String, size: Float, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (dim) alpha = 0.65f
    }

    private fun section(title: String) = text(title, 18f, bold = true).apply {
        pad(top = 24, bottom = 4)
        setTextColor(getColor(R.color.brand))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val RC_NOTIF = 1
        private const val RC_LOCATION = 2
        private const val RC_RINGTONE = 3
        private const val PICK_FROM_PHONE = "pick"
    }
}
