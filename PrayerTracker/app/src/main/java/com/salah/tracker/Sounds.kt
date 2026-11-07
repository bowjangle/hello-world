package com.salah.tracker

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings

/**
 * The reminder sound the user picked. Stored as a key:
 *   one of [BUILT_IN]'s keys, "default" (phone's notification sound), "silent",
 *   or "uri:<content uri>" for a sound picked from the phone.
 */
object Sounds {
    data class Option(val key: String, val label: String, val raw: Int? = null)

    const val DEFAULT_KEY = "bell"
    const val PHONE_DEFAULT = "default"
    const val SILENT = "silent"
    private const val URI_PREFIX = "uri:"

    // Ordered quietest → loudest.
    val BUILT_IN = listOf(
        Option("whisper", "Whisper (very quiet)", R.raw.snd_whisper),
        Option("bell", "Soft bell", R.raw.snd_bell),
        Option("marimba", "Marimba", R.raw.snd_marimba),
        Option("drop", "Water drop", R.raw.snd_drop),
        Option("chime", "Two-note chime", R.raw.snd_chime),
        Option("clear", "Clear chime (loudest)", R.raw.snd_clear),
    )

    val AUDIO_ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun uriKey(uri: Uri) = URI_PREFIX + uri

    /** null = no sound. */
    fun uriFor(ctx: Context, key: String): Uri? = when {
        key == SILENT -> null
        key == PHONE_DEFAULT -> Settings.System.DEFAULT_NOTIFICATION_URI
        key.startsWith(URI_PREFIX) -> Uri.parse(key.removePrefix(URI_PREFIX))
        else -> {
            val raw = (BUILT_IN.firstOrNull { it.key == key } ?: BUILT_IN.first { it.key == DEFAULT_KEY }).raw!!
            Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${ctx.packageName}/$raw")
        }
    }

    fun label(ctx: Context, key: String): String = when {
        key == SILENT -> "Silent"
        key == PHONE_DEFAULT -> "Phone's default sound"
        key.startsWith(URI_PREFIX) ->
            runCatching { RingtoneManager.getRingtone(ctx, uriFor(ctx, key))?.getTitle(ctx) }.getOrNull()
                ?.let { "Phone sound: $it" } ?: "Sound from phone"
        else -> (BUILT_IN.firstOrNull { it.key == key } ?: BUILT_IN.first { it.key == DEFAULT_KEY }).label
    }

    /**
     * Android freezes a notification channel's sound once it exists, so each sound gets its own
     * channel id. Switching sound = switching channel (the old one is deleted in ensureChannel).
     */
    fun channelId(key: String): String = when {
        key.startsWith(URI_PREFIX) -> "prayer_u" + Integer.toHexString(key.hashCode())
        else -> "prayer_s_$key"
    }

    // ---- preview ----
    private var playing: Ringtone? = null

    /** Plays [key] once at notification volume, so the preview is as loud as the real thing. */
    fun preview(ctx: Context, key: String) {
        stopPreview()
        val uri = uriFor(ctx, key) ?: return
        playing = RingtoneManager.getRingtone(ctx.applicationContext, uri)?.apply {
            audioAttributes = AUDIO_ATTRS
            play()
        }
    }

    fun stopPreview() {
        playing?.stop()
        playing = null
    }
}
