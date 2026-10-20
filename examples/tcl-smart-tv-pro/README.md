# TCL Smart TV Pro (Android 14 Google TV)

Tested on **TCL Smart TV Pro** running Android 14 (API 34, Kernel 5.15, Build `tcl9618-user 14 UTT2.250416.001 AU02 release-keys`).

## Hardware & System Profile

- **Device / Model**: TCL Smart TV Pro
- **Platform / Board**: MediaTek MT9618 (`tcl9618`)
- **OS**: Android 14 (Google TV)
- **Physical HDMI Ports**: 4 (`HW15` to `HW18`)
- **Tuner / Live TV**: `com.tcl.tvinput` (`HW2`)

## Safety Instructions (Critical)

> [!CAUTION]
> **NEVER disable `com.google.android.apps.tv.launcherx` on Android 14.**
> Google TV on Android 14 utilizes Direct Boot with Credential Encrypted (CE) storage. Disabling the stock launcher causes `system_server` crashes and triggers `RescueParty`, resulting in a black screen or recovery boot loop.

Instead, keep `launcherx` enabled and use the non-destructive **Accessibility Service (`HomeKeyService`)**:
```bash
adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.HomeKeyService
adb shell settings put secure accessibility_enabled 1
adb shell cmd package set-home-activity com.gathin.tvhome/.MainActivity
```

## TV Input Framework (TIF) Passthrough Mappings

Direct passthrough URIs for TCL TVs using `TvContract`:

| Port | Passthrough Input ID |
|---|---|
| **HDMI 1** | `com.tcl.tvinput/.TvPassThroughService/HW15` |
| **HDMI 2** | `com.tcl.tvinput/.TvPassThroughService/HW16` |
| **HDMI 3** | `com.tcl.tvinput/.TvPassThroughService/HW17` |
| **HDMI 4** | `com.tcl.tvinput/.TvPassThroughService/HW18` |
| **Live TV / Tuner** | `com.tcl.tvinput/.TunerInputService/HW2` |

> [!NOTE]
> Connected HDMI-CEC playback devices (like a PlayStation 5 or Apple TV) register a virtual child input with ID `HDMI100004` under `HW15`. The launcher automatically associates this state with HDMI 1.

## Debloating

To disable the sponsored TCL Channel aggregation and retail demo bloatware:

```bash
adb shell pm disable-user --user 0 com.tcl.waterfall.overseas
adb shell pm disable-user --user 0 com.tcl.channelplus
adb shell pm disable-user --user 0 com.tcl.exhibit
adb shell pm disable-user --user 0 com.tcl.esticker
adb shell pm disable-user --user 0 com.tcl.ocean.instructions
adb shell pm disable-user --user 0 com.tcl.suspension
```
