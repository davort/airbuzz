# AirBuzz app

Davor owns this; it is his first Android project, so explain Android steps he must take in plain words. The plan, decisions and look are in the private repo `davort/airbuzz-research` (`design/BRIEF.md`), which also holds the band research and the test app. Never copy code from it, or from any other app, into this repo, which is GPL-3.0 and meant to be public.

## Rules

- Every APK Davor installs gets a new `versionCode` and `versionName` in `app/build.gradle.kts` and a line under "Versions" in `README.md`.
- Every push to main runs `.github/workflows/checks.yml` (version bookkeeping, unit tests, debug APK). Before pushing a version bump, run `.github/version-check.sh origin/main`; when Checks fails on your push, fix main before anything else.
- Keep `THIRD_PARTY_NOTICES.md` current when adding code or assets from elsewhere. The band layer follows the MIT fitbit-air library; keep its notice.
- Nothing involving Bluetooth, the band, permissions, background running, notifications, calls, or how a screen looks on his phone is verified until it has run on his phone: an OPPO Find X9 Pro (CPH2791) on ColorOS 17, Android 17 (checked over adb on 11 Nov 2026). Orbs cannot run an Android emulator. Name what still needs the phone when you report.
- Record hardware results in `davort/airbuzz-research` `research/README.md` under "Confirmed on hardware".
- Uninstalling AirBuzz, clearing its data, or switching between debug and release signing deletes the step log and the settings. Before any step that does, make "Settings → Share log, and send it" the step right before it, not a note after the list. On 13 Nov a day of log was lost that way.

## Who changes what

Two kinds of thread work on this repo (Davor, 11 Nov 2026). Pull before every change, and give a change that spans both layers to one thread.

- **Design threads** (working from the Paper file "AirBuzz") own the look: `ui/`, `Theme.kt`, fonts and other UI resources.
- **App threads** own behaviour: `band/`, `Engine.kt`, `rules/`, `service/`, `data/`, the manifest and permissions, hardware debugging, and the research notes.
- A design that needs something the engine doesn't provide yet, such as a new state to show, goes to an app thread first; don't invent the signal in the UI.
- Whoever builds the APK takes the next `versionCode`.

## Checking UI in an orb

Robolectric can render Compose screens to PNG: temporarily add `testImplementation("org.robolectric:robolectric:4.14.1")` and `testOptions { unitTests.isIncludeAndroidResources = true }`, build `MainActivity`, `setContent` with the screen inside `AirBuzzTheme`, idle the looper with `ShadowLooper.idleMainLooper(40, MILLISECONDS)` a few times, and draw the decor view under `@GraphicsMode(NATIVE)`. Set state before `setContent`, never inside it, or recomposition loops forever. Remove both afterwards.
