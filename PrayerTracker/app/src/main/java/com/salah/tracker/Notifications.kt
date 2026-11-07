package com.salah.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.text.format.DateFormat
import java.util.Date

object Notifications {
    // Channel settings (sound, importance) are frozen by Android once created.
    // Bump this id if you ever change the sound so the new one takes effect.
    // v1 had a chime that was too quiet; v2 is the louder one.
    const val CHANNEL = "prayer_v2"
    private val OLD_CHANNELS = listOf("prayer_v1")

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach { if (nm.getNotificationChannel(it) != null) nm.deleteNotificationChannel(it) }
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val sound = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${ctx.packageName}/${R.raw.chime}")
        val ch = NotificationChannel(CHANNEL, "Prayer reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Prayer time alerts and \"did you pray?\" check-ins"
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun nid(p: Prayer) = 10 + p.ordinal

    private fun time(ctx: Context, millis: Long) = DateFormat.getTimeFormat(ctx).format(Date(millis))

    private fun base(ctx: Context, title: String, text: String): Notification.Builder {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(ctx.getColor(R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
    }

    private fun action(ctx: Context, label: String, actionName: String, rc: Int, w: PrayerWindow) =
        Notification.Action.Builder(
            null, label, Scheduler.pending(ctx, actionName, rc, w.prayer, w.date)
        ).build()

    fun showPrayerTime(ctx: Context, w: PrayerWindow) {
        val n = base(ctx, "${w.prayer.label} time", "It's ${time(ctx, w.start)} — time to pray.")
            .addAction(action(ctx, "I prayed ✓", Scheduler.ACTION_MARK_PRAYED, 200 + w.prayer.ordinal, w))
            .build()
        notify(ctx, w.prayer, n)
    }

    fun showCheck(ctx: Context, w: PrayerWindow) {
        val n = base(ctx, "Did you pray ${w.prayer.label}?", "Time ends at ${time(ctx, w.end)}.")
            .addAction(action(ctx, "Yes ✓", Scheduler.ACTION_MARK_PRAYED, 200 + w.prayer.ordinal, w))
            .addAction(action(ctx, "Not yet", Scheduler.ACTION_NOT_YET, 300 + w.prayer.ordinal, w))
            .build()
        notify(ctx, w.prayer, n)
    }

    /** Fires a sample notification right now so you can hear the sound without waiting for a prayer. */
    fun showTest(ctx: Context) {
        val n = base(ctx, "Test: Dhuhr time", "This is what prayer reminders look and sound like.").build()
        ctx.getSystemService(NotificationManager::class.java).notify(99, n)
    }

    /**
     * Reasons the chime would NOT be heard, in plain words. Empty list = should be audible.
     * These are phone settings the app can't override, only point you to.
     */
    fun soundProblems(ctx: Context): List<String> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        val out = mutableListOf<String>()
        if (!nm.areNotificationsEnabled()) return out // reported separately by the main screen
        val ch = nm.getNotificationChannel(CHANNEL)
        if (ch != null && (ch.importance < NotificationManager.IMPORTANCE_DEFAULT || ch.sound == null)) {
            out += "The \"Prayer reminders\" category is set to silent in Android settings."
        }
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) {
            out += "Do Not Disturb is on, which silences notifications."
        }
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> out += "Your phone is on silent."
            AudioManager.RINGER_MODE_VIBRATE -> out += "Your phone is on vibrate, so no sound plays."
        }
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL && am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) {
            out += "Notification volume is at 0."
        }
        return out
    }

    private fun notify(ctx: Context, p: Prayer, n: Notification) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(nid(p), n)
    }

    fun cancel(ctx: Context, p: Prayer) =
        ctx.getSystemService(NotificationManager::class.java).cancel(nid(p))

    fun clearAll(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
}
