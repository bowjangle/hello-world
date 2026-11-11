package com.salah.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.text.format.DateFormat
import java.time.LocalDate
import java.util.Date

object Notifications {
    /** The channel for the currently chosen sound. */
    fun channel(ctx: Context) = Sounds.channelId(Store(ctx).sound)

    /** Creates the channel for the chosen sound and removes channels for sounds no longer used. */
    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val key = Store(ctx).sound
        val id = Sounds.channelId(key)
        nm.notificationChannels
            .filter { it.id.startsWith("prayer") && it.id != id }
            .forEach { nm.deleteNotificationChannel(it.id) }
        if (nm.getNotificationChannel(id) != null) return
        val ch = NotificationChannel(id, "Prayer reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Prayer time alerts and \"did you pray?\" check-ins"
            setSound(Sounds.uriFor(ctx, key), Sounds.AUDIO_ATTRS)
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
        return Notification.Builder(ctx, channel(ctx))
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
        Prayer.entries.filter { it != w.prayer }.forEach { cancel(ctx, it) } // drop stale "X time" alerts
        val n = base(ctx, "${w.prayer.label} time", "It's ${time(ctx, w.start)} — time to pray.")
            .addAction(action(ctx, "I prayed ✓", Scheduler.ACTION_MARK_PRAYED, 200 + w.prayer.ordinal, w))
            .build()
        notify(ctx, w.prayer, n)
    }

    private const val NAG_ID = 50

    /**
     * The single "did you pray?" reminder. Shows the oldest unchecked prayer, plus how many others
     * are waiting. [alert] = false updates it quietly (after you check one off) and does nothing
     * if you had dismissed it — the next 10-minute reminder will bring it back.
     */
    fun showNag(ctx: Context, pending: List<PrayerWindow>, alert: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (pending.isEmpty()) { nm.cancel(NAG_ID); return }
        if (!alert && nm.activeNotifications.none { it.id == NAG_ID }) return
        if (!nm.areNotificationsEnabled()) return

        val w = pending.first()
        val now = System.currentTimeMillis()
        val last = Store(ctx).finalShownFor == LocalDate.now()
        val others = pending.drop(1).joinToString(", ") { it.prayer.label }
        val title: String
        val text: String
        if (last) {
            title = "Last reminder today: ${w.prayer.label} not checked"
            text = (if (others.isNotEmpty()) "Also unchecked: $others. " else "") +
                "This is the last notification — no more reminders until tomorrow."
        } else {
            title = if (now < w.end) "Did you pray ${w.prayer.label}?" else "You haven't checked ${w.prayer.label}"
            text = (if (now < w.end) "Time ends at ${time(ctx, w.end)}." else "Tap ✓ once you've prayed it.") +
                (if (others.isNotEmpty()) "  Also unchecked: $others." else "")
        }
        cancel(ctx, w.prayer) // the "X time" alert for this prayer is now redundant
        val b = base(ctx, title, text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(false)
            .addAction(action(ctx, "Yes, prayed ✓", Scheduler.ACTION_MARK_PRAYED, 400, w))
        // "Not yet" would be a lie on the last one: there is no "later" tonight.
        if (!last) b.addAction(Notification.Action.Builder(null, "Not yet", PendingIntent.getBroadcast(
            ctx, 401, Intent(ctx, AlarmReceiver::class.java).setAction(Scheduler.ACTION_NOT_YET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )).build())
        val n = b.build()
        nm.notify(NAG_ID, n)
    }

    fun cancelNag(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NAG_ID)

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
        if (Store(ctx).sound == Sounds.SILENT) return out // you chose silence; nothing to warn about
        val ch = nm.getNotificationChannel(channel(ctx))
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

}
