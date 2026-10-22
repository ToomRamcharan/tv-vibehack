# 🖱️ NLRC Setup

Desktop app (Windows, macOS, Linux) that installs the NLRC launcher (`com.gathin.tvhome`) on an Android TV over Wi-Fi and makes it the home screen, with no terminal and no adb install needed. It can also **Restore** the TV's original home screen.

End users: see **Easy install (no coding)** in the [root README](../README.md). This file is for people working on the app.

Built with [Tauri 2](https://tauri.app): a Rust backend that talks to the TV with the pure-Rust [`adb_client`](https://crates.io/crates/adb_client) crate, and a [Svelte 5](https://svelte.dev) + [daisyUI 5](https://daisyui.com) (Tailwind 4) front end built with Vite. Everything is bundled, including the Inter font, so the app works offline.

---

## 🧭 How it works

The app is a 5-step wizard: **Welcome → Prepare your TV → Connect → Working → Done**.

1. **Connect:** it scans the computer's local /24 network for port `5555`, or the user types an IP. On first connect the TV shows _Allow debugging from this computer?_. The app keeps retrying for up to 60 s while the user accepts. The adb key is saved in the app's data folder (file mode `0600`), so "Always allow" sticks across runs.
2. **Choosing the home-screen method** (`detect.rs`):

   | TV                                                                        | Method                                                                                                      |
   | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
   | `com.google.android.apps.tv.launcherx` installed (Android 14 Google TV)   | **Accessibility**: turn on `HomeKeyService`. Never disables launcherx (doing so bootloops these TVs).       |
   | `com.google.android.tvlauncher` installed **and** Android ≤ 11 (SDK ≤ 30) | **Disable stock**: `pm disable-user` on tvlauncher, plus PatchWall (`com.mitv.tvhome.atv`) if it's enabled. |
   | anything else                                                             | "Not supported yet." Stops before changing anything.                                                        |

3. **Install** (`flow.rs`):
   - Install the bundled APK. On "not enough space" it runs `pm trim-caches 999G` and retries once. If an older install has a different signature, it asks the user before uninstalling it.
   - Grant `WRITE_SECURE_SETTINGS` and the overlay permission.
   - Make it home with the chosen method, then launch it.
   - Verify the result.

   Packages the app disables are recorded on the TV in `settings global nlrc_setup_disabled`.

4. **Restore:**
   - Remove only our accessibility service, keeping other apps' services.
   - Re-enable what we disabled, plus the stock launchers as a safety net.
   - Uninstall the launcher, but only once a stock home screen is confirmed back.
   - Press Home.

   Restore never disables anything.

Every error shown to the user is plain language. Raw adb output appears only as a "(details: …)" suffix. If Install or Restore runs longer than 5 minutes (for example, the Wi-Fi dropped), the app gives up and offers **Reconnect**.

---

## 📁 Layout

```
installer/
├── package.json                 # Tauri CLI + scripts
├── ui/                          # Svelte front end (own package.json; builds to ui/dist)
│   └── src/
│       ├── App.svelte           # window shell: step rail, screen switcher, close guard
│       ├── app.css              # Tailwind + daisyUI themes (light "nlrc", dark "nlrc-dark")
│       ├── lib/wizard.svelte.js # wizard state, backend calls, step events
│       ├── lib/Icon.svelte      # inline icons
│       ├── lib/i18n.js          # picks the language, t() for screen text, fromBackend() for Rust messages
│       ├── locales/             # en, pt, es, fr, it, de, nl, ru, zh, te, hi (.json)
│       └── screens/             # Welcome, Prepare, Connect, Working, Done
└── src-tauri/
    ├── tauri.conf.json          # app config (window, icons, macOS ad-hoc signing)
    ├── tauri.bundle.conf.json   # adds resources/nlrc.apk — used only for release bundles
    ├── capabilities/            # Tauri permissions for the UI
    ├── resources/nlrc.apk       # launcher APK (git-ignored, put here for bundling)
    ├── examples/probe.rs        # CLI to test a TV connection without the UI
    └── src/
        ├── tv.rs        # Tv trait (shell + install) and TvError
        ├── adb.rs       # real TV over adb_client + adb key handling
        ├── detect.rs    # read model/SDK/packages, choose the method
        ├── a11y.rs      # add/remove our service in the accessibility list
        ├── flow.rs      # install_flow / restore_flow
        ├── scan.rs      # find TVs on the local network
        ├── commands.rs  # Tauri commands, progress events, timeouts, error wording
        └── fake.rs      # FakeTv for tests
```

The UI calls four commands: `scan_tvs`, `connect_tv`, `run_install({ allowReplace })` and `run_restore`. It listens for `step` events to tick off the checklist.

---

## 🛠️ Development

Needs Rust (1.90+), Node 20.19+ (or 22.12+), and on Linux the WebKitGTK 4.1 dev packages (`libwebkit2gtk-4.1-dev` and friends).

```bash
cd installer
npm install
npm install --prefix ui
npm run tauri dev          # starts the UI dev server with hot reload and opens the app; works without the APK
```

Plain `tauri dev` has no launcher APK inside, so **Install** stops with "The launcher file is missing" (Restore works). To test Install on a TV, build the launcher's debug APK and use:

```bash
(cd ../launcher && ./gradlew assembleDebug)
npm run dev:tv             # copies app-debug.apk into src-tauri/resources/ and runs tauri dev with it
```

On Linux the app sets `WEBKIT_DISABLE_DMABUF_RENDERER=1` at startup (`main.rs`), because WebKitGTK otherwise crashes on NVIDIA + Wayland with "Error 71 dispatching to Wayland display".

`tauri dev` and `tauri build` run the UI's Vite server or build for you (`beforeDevCommand` and `beforeBuildCommand` in `tauri.conf.json`). To work on the screens only, run `npm run dev --prefix ui`. Outside the app window the backend calls fail, so this is only useful for layout work.

Tests and lint:

```bash
cd installer/src-tauri
cargo test
cargo clippy --all-targets -- -D warnings
cargo fmt --check
npm run check --prefix ../ui   # svelte-check: types and accessibility
```

The TV logic is tested against `FakeTv`, which records every shell command and models how the TV's `sh -c` splits arguments. When you change a command, assert the exact command string in the test.

Check a real TV without the UI:

```bash
cargo run --example probe -- 192.168.1.50                       # model, Android version, packages
cargo run --example probe -- 192.168.1.50 path/to/launcher.apk  # also installs
```

---

## 📦 Building a release

Local bundle (installer for the current OS):

```bash
cd launcher && ./gradlew assembleRelease          # or assembleDebug for testing
cp app/build/outputs/apk/release/app-release.apk ../installer/src-tauri/resources/nlrc.apk
cd ../installer && npm run bundle
```

Use `npm run bundle`, **not** `npm run tauri build`: only the bundle config includes the APK. A plain `tauri build` produces an app with no launcher inside.

CI: [`.github/workflows/installer-release.yml`](../.github/workflows/installer-release.yml) runs on tags `setup-v*`, for example `git tag setup-v0.1.0 && git push --tags`:

1. It builds a signed release APK. It fails rather than ship an unsigned one.
2. It bundles the Windows, macOS (universal) and Linux installers.
3. It attaches them to a **draft** GitHub Release.

It needs the four signing secrets described in [CONTRIBUTING.md](../CONTRIBUTING.md#release-signing).

The apps are not signed with paid certificates. The macOS build is ad-hoc signed so it opens at all. Users still see a first-open warning, and the Prepare screen explains how to get past it.

---

## 🌐 Languages

Same 11 languages as the launcher: English, Portuguese, Spanish, French, Italian, German, Dutch, Russian, Chinese, Telugu and Hindi. The app follows the computer's language (like the launcher follows the TV's) and falls back to English. There's no language picker.

Each `ui/src/locales/<lang>.json` has two parts:
- `ui`: screen text by key. Use `**bold**` for bold text.
- `backend`: translations of the Rust backend's messages. The key is the message's **English text**, exactly as the backend writes it. Raw adb output in `(details: …)` stays untranslated.

When you add or change a user-facing message in Rust, put it in a constant, add it to `every_message_is_translated` in `commands.rs`, and add the translation to all 10 non-English files. That test fails if a language is missing a message or a screen key.

The bundled Inter font covers Latin, Greek and Cyrillic. Chinese, Hindi and Telugu use the computer's own fonts.

---

## ⚠️ Rules when changing TV commands

- **Never** add a code path that can run `pm disable*` on `com.google.android.apps.tv.launcherx`.
- Restore must never disable anything.
- Quote any value built from TV data before it goes into a shell command (see `quoted()` in `flow.rs`).
- Keep every user-facing string in plain language. No adb jargon on screen.

## 🚧 Known limits (v1)

- Only TVs reachable at `<ip>:5555`. No Android 11+ pairing codes and no USB.
