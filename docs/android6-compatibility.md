# Android 6 compatibility

The launcher can be installed from Android 6.0 (API 23). compileSdk 34 and targetSdk 29
are unchanged. This is application compatibility; no TV firmware update is involved.

Android 6/7 use system font families. Android 8+ continue to use the original Outfit
font through `values-v26/fonts.xml`; XML variable-font weights are in `values-v28/fonts.xml`,
where that attribute is supported. Direct Boot declarations are
retained for systems that support them; API 23 ignores those declarations.

Some vendor builds expose a non-null `TvInputManager` whose backing `ITvInputManager`
is null. `TvInputCompat` treats an unavailable/inaccessible service as an empty input
list, so home startup and the standard Inputs menu stay usable. A working service
still returns its original input list.

Build and run the dependency-free framework smoke test on a test device:

```sh
cd launcher
./gradlew assembleDebug assembleDebugAndroidTest '-PinstrumentationRunner=com.gathin.tvhome.Android6Smoke'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.gathin.tvhome.test/com.gathin.tvhome.Android6Smoke
```

Use a test installation: installing this package over an existing launcher replaces
that launcher. The smoke test inflates the affected layouts and explicitly starts
the home activity; it does not grant accessibility or secure-settings permissions.
On vendor API-23 builds, prefer a clean test build: an incrementally updated multi-DEX
test APK was not loadable by the tested firmware's instrumentation loader. A single-DEX
test harness was used for the device run; production multidex settings are unchanged.

Device evidence and remaining limitations are recorded in the associated PR.
One tested MiTV4X firmware is not a guarantee for every Android 6 TV or every vendor API.
Native HDMI selection and MStar amplifier synchronization are separate opt-in features.
