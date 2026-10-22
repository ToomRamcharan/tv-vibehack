# 📺 NLRC Home (launcher)

The Android TV launcher at the heart of tv-vibehack. Package `com.gathin.tvhome`, app name **NLRC Home**, version `2.1` (`versionCode 3`).

What it does for viewers is described in the [root README](../README.md). This file is for building, installing and changing it.

---

## 🛠️ Build

Needs **JDK 17** and the **Android SDK** (compile SDK 34). If your default Java isn't 17, point `JAVA_HOME` at a JDK 17 first.

```bash
cd launcher
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release-unsigned.apk
```

Release builds are signed only when the `NLRC_KEYSTORE*` environment variables are set. The CI release workflow sets them. See [Release signing in CONTRIBUTING.md](../CONTRIBUTING.md#release-signing).

| Setting | Value | Why |
|---|---|---|
| `minSdk` | 26 (Android 8) | Oldest TVs it runs on |
| `targetSdk` | 29 | Kept on purpose: raising it changes storage and permission behavior the PenDrive Viewer relies on. Lint's `ExpiredTargetSdkVersion` check is disabled because the app is sideloaded, not on Play. |
| `crunchPngs false` (release) | | Some `.png` resources are really JPEGs, which the PNG cruncher rejects |

`build-and-install.sh` builds the APK and installs it in one go (`TV_HOST=<ip> ./build-and-install.sh`). It looks for JDK 17 and the SDK only in common macOS locations, so on Linux set `JAVA_HOME` and `ANDROID_HOME` yourself, or use plain `./gradlew`.

---

## 📲 Install on a TV

**Easiest:** use the NLRC Setup app. While developing, `cd installer && npm run dev:tv` puts your fresh `app-debug.apk` in it (see [installer/README.md](../installer/README.md)).

**By hand with adb:**

```bash
adb connect <TV_IP>:5555
adb -s <TV_IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

- Always pass `-s`. A phone plugged in over USB makes adb stop with "more than one device".
- "Not enough space": run `adb shell pm trim-caches 999G`, then retry.
- Install stalls on weak Wi-Fi: use `tools/b64push.py`, or install from a USB stick with the TV's file app.
- adb hangs, even on `kill-server`: run `pkill -9 -x adb`, then reconnect.

Then make it the home screen. The method depends on the TV:

| TV | Command | Undo |
|---|---|---|
| **Android 14 Google TV** (Acer, TCL) | `adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS`<br>`adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.HomeKeyService`<br>`adb shell settings put secure accessibility_enabled 1` | `adb shell settings delete secure enabled_accessibility_services` |
| **Android 9–11** (Mi Box, Mi TV) | `adb shell pm disable-user --user 0 com.google.android.tvlauncher` (Mi TVs with PatchWall: also `com.mitv.tvhome.atv`) | `adb shell pm enable <same package>` |

> ⚠️ **Never disable `com.google.android.apps.tv.launcherx` on Android 14.** It bootloops the TV. `set-home-activity` doesn't stick on Android 9, which is why disabling is used there.

The `WRITE_SECURE_SETTINGS` grant lets `BootReceiver` switch `HomeKeyService` back on after every reboot.

---

## 🗂️ Source map

`app/src/main/java/com/gathin/tvhome/`

| File | Role |
|---|---|
| `MainActivity.java` | Home screen: clock, greeting, dock, top buttons, app version |
| `AppAdapter.java` | App and input tiles, and the hold-OK tile menu (pin, move, hide, app info) |
| `DockDecoration.java` | The lighter band behind the dock row, and the gap below it |
| `GlassView.java` | The frosted-glass focus cursor that glides between tiles |
| `HomeKeyService.java` | Accessibility service: Home key back to NLRC on Google TV, and Netflix/Prime buttons to HDMI 2 |
| `HdmiHelper.java` | Switches straight to the HDMI 2 passthrough input (Realtek) |
| `BootReceiver.java` | Opens NLRC on boot and re-enables `HomeKeyService` |
| `PenDriveActivity.java`, `VideoPlayerActivity.java` | Video list from TV storage and USB drives, and the player |
| `Fonts.java` | Re-applies Outfit font weights on Android 9 |

Resources live in `app/src/main/res/`. Text is in `values*/strings.xml` in 11 languages: en (default), pt, es, fr, it, de, nl, ru, zh, te and hi.

---

## ⚠️ Gotchas when changing it

- **Strings:** add every new string to **all 11** `strings.xml` files. The app follows the TV's language, so don't force a `Locale`.
- **Dock order and hidden apps** persist in device-protected SharedPreferences (`dock.xml`), because the app is `directBootAware`. `R.array.dock_apps` is only the default.
- **HDMI 2 / Netflix / Prime redirect** targets a Realtek input ID from the author's Acer TV. It is gated by `HdmiHelper.isAvailable()`. Keep that gate.
- **`HomeKeyService`** only watches `launcherx`, so it does nothing on Android 9 devices.
- The **"ENTERTAINMENT HUB"** tagline is hardcoded in `activity_main.xml`, not taken from `@string/nlrc_tagline`.
- **Signing key:** an APK signed with a different key can't update an installed one. The user must uninstall first, which resets their dock. Keep the release key safe.

---

## 🔍 Testing on a TV

```bash
adb exec-out screencap -p > shot.png                       # screenshot (blocked on some Mi TVs →)
adb shell uiautomator dump && adb shell cat /sdcard/window_dump.xml | grep -o 'text="[^"]*"'
adb shell input keyevent --longpress DPAD_CENTER           # opens the tile menu
adb shell run-as com.gathin.tvhome cat /data/user_de/0/com.gathin.tvhome/shared_prefs/dock.xml
```

The tile menu opens through key repeat in `AppAdapter.Holder`, not a View long-click, which is why `--longpress DPAD_CENTER` works.

`run-as` only works with debug builds.
