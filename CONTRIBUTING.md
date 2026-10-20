# Contributing

Thanks for helping more TVs lose their ads. Three kinds of contributions are especially welcome.

## 1. Add your TV model

Every brand ships different bloat. If you ran the prompt (or did it by hand) on a TV that isn't in
`examples/`, add it:

1. Create `examples/<brand>-<model>/debloat.txt`. Copy the format from
   `examples/xiaomi-mi-tv-4a/debloat.txt`: one package per line, grouped with a comment that says
   what the group is, and a trailing comment on anything non-obvious ("re-enable for firmware
   updates", "needed for HDMI-CEC", etc.).
2. Put the exact model name, Android version and firmware build in the header so people can tell
   whether it matches theirs. `tools/tv.py --probe` prints all three.
3. Only list packages you have actually disabled and lived with for a few days. Note anything that
   broke and what you re-enabled.
4. Add a row to `examples/README.md`.
5. If your TV's Live TV app or stock launcher has a different package name, say so in the header;
   `tools/restore.sh` and `launcher/build-and-install.sh` accept them as arguments.

Never list packages to *uninstall*. Everything in this repo disables with `pm disable-user`, which
is reversible from the TV's own settings if the tools are gone.

## 2. Improve the launcher

The launcher lives in `launcher/` and is deliberately small: plain Java, AndroidX RecyclerView and
DynamicAnimation, no Kotlin, no Compose, no network. It targets TVs with 1 GB of RAM, so:

- No blur, no video backgrounds, no polling, no background services.
- Anything that depends on a specific TV goes in `app/src/main/res/values/config.xml`, not in code.
- Keep D-pad navigation deterministic. Test Up/Down/Left/Right from every edge.
- Verify on a real TV, not only the emulator. The easiest way is remote key injection plus a
  screenshot: `tools/tv.py 'input keyevent KEYCODE_DPAD_DOWN; screencap -p /sdcard/s.png'` then
  `tools/tv.py --pull /sdcard/s.png s.png`. Include a screenshot in your pull request.

Build with `TV_HOST=<ip> launcher/build-and-install.sh` (JDK 17, Android SDK platform 34 and
build-tools 34.0.0; on macOS `brew install openjdk@17 android-commandlinetools` then
`sdkmanager "platforms;android-34" "build-tools;34.0.0"`).

Build with plain `./gradlew assembleDebug` if `build-and-install.sh` can't find JDK 17 at its
Homebrew path; Gradle finds an installed JDK 17 by itself.

All on-screen text lives in `app/src/main/res/values*/strings.xml` (11 languages). When you add a
string, add it to every one of those files, or mark it `translatable="false"` if it's a brand or
technical name. `./gradlew lintDebug` flags any language you missed.

Ideas that fit the direction: an idle screensaver with clock and ambient colour, per-TV icon packs
for apps without banners, more languages.
Ideas that don't: recommendation rows, content feeds, ads, telemetry.

## 3. Improve the prompt and tools

`PROMPT.md` is what most people will actually use. If your assistant got stuck somewhere, the fix
usually belongs in the prompt as one extra sentence, not in a script. Keep it brand-neutral; put
brand-specific knowledge in `examples/`.

`tools/tv.py` and `tools/b64push.py` exist because the stock `adb` is blocked on recent macOS. If
you find a cleaner workaround, open an issue first.

## Pull requests

- One topic per PR. Small is fine.
- Say which TV you tested on.
- No new dependencies in the launcher without discussing it in an issue.
- Be kind in reviews. Most contributors here are doing this for their parents.

By contributing you agree your work is released under the repo's MIT licence.
