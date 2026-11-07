# Salah Tracker (Android)

A small, offline Android app that tracks whether you prayed your five daily prayers.

## What it does

1. **At each prayer time** – a short, soft two-note chime notification ("Dhuhr time"). No vibration, no looping sound.
   If you pray right away you can tap **I prayed ✓** and you won't be asked again.
2. **30 minutes later** – "Did you pray Dhuhr?" with **Yes ✓** / **Not yet**.
3. **Every 10 minutes after that** – the same question until you tap **Yes**, or until that prayer's time ends:

   | Prayer  | Reminders stop at |
   |---------|-------------------|
   | Fajr    | Sunrise |
   | Dhuhr   | Asr |
   | Asr     | Maghrib |
   | Maghrib | Isha |
   | Isha    | Middle of the night (so you aren't pinged all night) |

The main screen shows today's 5 prayers with checkboxes (tick or untick any time) and the last 7 days (`✓ ✓ ✗ ✓ ✓  4/5`).

Prayer times are calculated on the phone from your coordinates (using the [Adhan](https://github.com/batoulapps/adhan-java) library) – no internet, no account, no ads. Choose your calculation method (MWL, ISNA, Umm al-Qura, Karachi, …) and Asr convention (Standard / Hanafi) in **Settings**.

## Install

**Easiest:** download `app-debug.apk` from the latest run of the *Build Android APK* GitHub Action (Actions tab → latest run → Artifacts), unzip it, copy it to your phone and open it. Android will ask you to allow installing from that source.

**Or build it yourself:** open the `PrayerTracker` folder in Android Studio and press Run, or:

```sh
cd PrayerTracker
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

## First launch

1. Allow notifications.
2. Settings opens automatically: tap **Use my current location** (or type latitude/longitude), pick your calculation method, **Save**.
3. Check the times match your local mosque. If they're off by a few minutes, try a different calculation method.

## Known limitations

- Some phones (Xiaomi, Huawei, Oppo, Samsung with aggressive battery saving) kill background alarms. If reminders don't arrive, set the app's battery usage to **Unrestricted**.
- If you travel, update the location in Settings – it is not tracked automatically.
- Want a different sound? Replace `app/src/main/res/raw/chime.wav` **and** change the channel id in `Notifications.kt` (Android locks a channel's sound after first install). Or just change it in Android's notification settings for the app.
