# tv-vibehack

Android TV launcher (`launcher/`, package `com.gathin.tvhome`) plus Python adb helpers (`tools/`).

## Project rules (`.claude/`)
- Read `.claude/rules/code.md` and `.claude/rules/graphify.md` before coding; they override defaults
- Git: never commit/push to `main`; work on `feature/<description>` or `bugfix/<description>` and ask the user to create it (don't create it yourself)
- Never commit unless the user asks; message format `{gitmoji} {Add|Update|Fix} {message}` (no ticket IDs); no `Co-Authored-By` / AI attribution
- Ask before destructive actions (deleting files, wholesale overwrites, force-push); plan first for multi-step work
- Commands: `/new-feature <desc>`, `/bug-fix <desc>` (both stop for approval after the plan/diagnosis), `/graphify-run [path]`
- Skills in `.claude/skills/`: for this codebase use `engineering-mobile-app-builder` (rules mention `engineering-frontend-developer`, which doesn't exist here), `karpathy-guidelines`, `graphify`, `i-have-adhd`
- graphify: if `graphify-out/graph.json` exists, `graphify query "<q>"` before grepping; after code edits run `graphify update .`
- The rules/commands came from a web/npm project - apply them here as: lint/test gate = `./gradlew assembleDebug` + on-device check (no npm, no unit tests yet); i18n = every `res/values*/strings.xml`, not `src/assets/i18n`; ignore DB migration / variant (gss, saxony) steps

## Build & install
- `cd launcher && ./gradlew assembleDebug` - JDK 17 is pinned via `gradle/gradle-daemon-jvm.properties`; works even if shell JAVA_HOME is Java 8
- `adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk` - always pass `-s`; a USB phone is often attached too ("more than one device")
- `build-and-install.sh` hardcodes JAVA_HOME=/opt/homebrew/opt/openjdk@17, which may not exist - prefer plain `./gradlew`
- Install fails "not enough space" → `adb shell pm trim-caches 999G` frees cache safely
- Flaky Wi-Fi TVs: streamed install stalls mid-transfer; fall back to `tools/b64push.py` (chunked) or installing from a USB stick via the TV's file app
- adb hangs (even `kill-server`) → `pkill -9 -x adb`, then reconnect

## Device setup (Android 9 Xiaomi: Mi Box / Mi TV)
- Stock home is `com.google.android.tvlauncher`; `set-home-activity` doesn't stick → `pm disable-user --user 0 com.google.android.tvlauncher` (safe on Android 9; Settings FallbackHome remains)
- Never disable `launcherx` on Android 14 Google TV (bootloop) - there `HomeKeyService` accessibility intercept is used instead
- `HomeKeyService` only watches `launcherx`; it does nothing on Android 9 devices

## Code gotchas
- HDMI 2 / Netflix / Prime redirect targets a Realtek input ID (author's Acer TV); gated by `HdmiHelper.isAvailable()` - keep that gate
- Dock order + hidden apps persist in device-protected SharedPreferences (`dock.xml`) because the app is directBootAware; `R.array.dock_apps` is only the default
- UI text lives in `res/values*/strings.xml` (11 langs: en default, pt es fr it de nl ru zh te hi); add new strings to every file. App follows TV locale - don't force a Locale
- "ENTERTAINMENT HUB" tagline is hardcoded in `activity_main.xml`, not from `@string/nlrc_tagline`

## Testing on device
- `adb exec-out screencap -p > f.png` for screenshots (blocked on some Mi TVs → use `uiautomator dump` + grep `text=`)
- `input keyevent --longpress DPAD_CENTER` opens the tile menu (handled via key repeat in `AppAdapter.Holder`, not View long-click)
- Inspect saved state: `adb shell run-as com.gathin.tvhome cat /data/user_de/0/com.gathin.tvhome/shared_prefs/dock.xml`
