# Xiaomi MiTV4X native input chooser

Verified device: MiTV4X, MStar mainz, Android 6.0/API 23, firmware 8.10.15.652.
Android 6 application installation support is tracked separately in #7.

On this firmware, the TV-player external-source activity enters the tuner instead
of displaying HDMI choices. Use the native settings receiver to open the chooser:

```xml
<!-- launcher/app/src/main/res/values/config.xml -->
<string name="native_input_picker_action" translatable="false">com.xiaomi.mitv.settings.INPUTSOURCE_POPUP</string>
<string name="native_input_picker_receiver" translatable="false">com.xiaomi.mitv.settings/.SettingsBroadcastReceiver</string>
```

Both the top Inputs button and the optional `native_inputs` tile use this receiver.
To pin that tile, add `native_inputs` to the existing `dock_apps` configuration in
the position you want. No global dock defaults or tuner-package settings change.

The receiver must exist, be enabled/exported, and not require an unavailable permission.
An empty, inaccessible or missing configuration keeps the original TIF dropdown.
The broadcast has no result acknowledging that the overlay became visible; firmware
that accepts but ignores it needs its own verification. Other Xiaomi models are untested.

## Verification

The contribution patch opened the native HDMI chooser from both controls without
automatically selecting a source. See the PR for the screenshots and device results.
The test runner checks component access without requiring Xiaomi hardware:

```sh
cd launcher
./gradlew assembleDebug assembleDebugAndroidTest '-PinstrumentationRunner=com.gathin.tvhome.NativeInputPickerChecks'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.gathin.tvhome.test/com.gathin.tvhome.NativeInputPickerChecks
```

Test on a spare/test installation because the default package name is the launcher.
No firmware files, vendor libraries, private addresses or uninstall instructions are included.
