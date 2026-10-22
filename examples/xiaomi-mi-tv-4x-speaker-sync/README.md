# MiTV4X / MStar speaker synchronization

Device: MiTV4X, Android 6.0/API 23, firmware 8.10.15.652, MStar mainz.
Android 6 installation support is tracked independently in #7. This workaround is
disabled by default and does not establish that all MStar TVs need this mapping.

## Symptom and diagnosis

Multiple apps were barely audible. The media slider and vendor database followed
volume changes, while the native amplifier stayed at an old low value. For example,
media 19/50 corresponded to database 38 but actual speaker level 8.
`TvAudioManager.getSpeakerVolume()` and the database-labelled field printed by
`dumpsys mstar.AudioManager` can therefore mislead diagnosis.

Read the actual value with the firmware API:

```java
TvManager.getInstance().getAudioManager()
        .getAudioVolume(EnumAudioVolumeSourceType.E_VOL_SOURCE_SPEAKER_OUT);
```

The bridge reflects this API so vendor libraries are not bundled or required on
other devices. It uses the matching native `setAudioVolume(source, byte)` and
does not write the separate database wrapper or install a loudness effect.

## Explicit firmware opt-in

Pause playback and lower media volume to 3..5/50 **before installing an enabled build**.
The first startup may correct an amplifier that was much lower than the visible slider.
Configure `launcher/app/src/main/res/values/config.xml`:

```xml
<bool name="mstar_speaker_sync_enabled">true</bool>
<string-array name="mstar_speaker_sync_fingerprints">
    <item>Xiaomi/vforvendettas/vforvendetta:6.0/MRA58K/652:user/release-keys</item>
</string-array>
<integer name="mstar_speaker_sync_media_max">50</integer>
```

On this firmware the workaround scales Android media 0..50 to native speaker 0..100.
It runs only for an allowlisted firmware, the expected media range, the built-in
speaker route and an unmuted stream. Unknown native readings are ignored. Volume
and route are checked again before writing to avoid applying stale input.

The manifest receiver is disabled when the opt-in boolean is false, avoiding process
starts on volume changes for default installations. When enabled, it handles
media-volume/mute/route events, boot and package updates;
activity resume also retries. There is no polling or foreground/background service.
If the firmware API is absent or fails, the bridge logs and returns. Bluetooth,
headphones, ARC and other routes are deliberately outside the validated profile.

To disable the workaround, install a build with the boolean false. This stops future
synchronization and does not raise or restore the amplifier to an arbitrary value.

## Validation

The local repair was verified at media 3/4/5 and native 6/8/10, in both directions,
with a video app foregrounded and after the launcher process was reclaimed.
The owner confirmed that a low native level of 20 restored useful loudness.
No high-volume, reboot or external-output validation is claimed.

Framework-only checks need no additional test library:

```sh
cd launcher
./gradlew assembleDebug assembleDebugAndroidTest '-PinstrumentationRunner=com.gathin.tvhome.MstarSpeakerChecks'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.gathin.tvhome.test/com.gathin.tvhome.MstarSpeakerChecks
```

An additional hardware check is explicitly requested with `-e hardware true` before `-w`.
It requires the tested model/API, unmuted media at 3..5/50, and an existing native
level no higher than 10. It lowers the native level, verifies profile/range/mute/event
guards and restoration, then restores the saved low native value. Keep playback paused.
Run tests on the default-off build; the hardware runner supplies its profile in memory.
The complete 0..100 range is checked only as arithmetic, not as audible output.
