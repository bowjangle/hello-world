package com.salah.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.text.format.DateFormat
import java.util.Date

object Notifications {
    // Channel settings (sound, importance) are frozen by Android once created.
    // Bump this id if you ever change the sound so the new one takes effect.
    private const val CHANNEL = "prayer_v1"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
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
