# AirBuzz

AirBuzz makes a Google Fitbit Air vibrate for calls from people you choose and messages from apps you choose. Nothing else buzzes.

Works with Google Fitbit Air. AirBuzz is not made by, endorsed by or affiliated with Google or Fitbit. It talks to the Air over an unofficial protocol, so a firmware update could stop it working.

## Versions

- **0.1** (versionCode 1): first build of the real app. Installed on Davor's phone over adb (Play Protect blocks browser installs of apps with a notification listener); it warned that `libgg.so` and `libxp.so` weren't 16 KB-aligned.
- **0.2** (versionCode 2): Golden Gate libraries linked for 16 KB pages; no permanent status notification or foreground service (the notification listener keeps AirBuzz running); Test buzz no longer counts on Home's buzz strip.
- **0.3** (versionCode 3): the approved look from the first design pass (brief decisions 20–32): Google Sans Flex, Google Sans Code, Instrument Serif and Geist Pixel; cool grey palettes; slim segmented buttons; Pause from the Home switch with a paused card; "ignored" alerts; Activity one day at a time with totals and ignored-by-source rows; Test buzz with the front drawing of the Air, a 0–8 s timeline and Show details.

## What it does

- **Calls**, from any app that rings (phone, WhatsApp, Signal…): the Air buzzes every second until you answer, decline or press Silence, for up to 60 s. Who can ring is Chosen people (the default), Contacts, or Anyone. Chosen people ring through Pause and the quiet rules.
- **Messages** from chosen apps: one, two or three taps. Messages from the same app within 15 s share one buzz.
- **Stay quiet** at night, in Do Not Disturb, and while you use the phone (all on by default).
- **Activity**: the last three alerts on Home and a week of history, each with what AirBuzz did and why.
- **Test buzz** shows how long the Air took, step by step.
- **Appearance**: System, Light or Dark, with an accent colour for each.

## How it is put together

| Folder | What it does |
|---|---|
| `band/` | `AirBand` drives the Air over Bluetooth LE and Golden Gate: connect (or reuse the open link), wait for Google Health's traffic to go quiet, open a DTLS session with the band's public `BOOTSTRAP` key, write the haptic strength to make it buzz, then hand the channel back to Google Health. |
| `rules/` | Pure decisions: who may ring, which apps tap, quiet rules, the 15 s merge, phone number matching with libphonenumber. Unit tested. |
| `service/` | `AlertListener` (a notification listener) recognises incoming calls and messages. Android keeps a notification listener running, so there is no foreground service or permanent notification; the only notification is "Ringing your Air" with Silence during a call. |
| `data/` | Settings and the week's activity log, kept on the phone only. |
| `ui/` | Jetpack Compose screens in the approved look (design pass in Paper, brief decisions 20–32): rules as sentences with tinted blanks that each open one bottom sheet. `Theme.kt` holds the colour and type tokens. |
| `Engine.kt` | Connects the two: one queue of band commands, where a call goes ahead of messages. |

Choices that came from measuring on a real Air (test app 0.2–0.4 in the private research repo):
- **The open link.** A listen-only Bluetooth client stays connected between buzzes; the secure session is still closed and handed back to Google Health after each one. That took a message buzz from a 3.1 s median to 0.5 s, and Google Health kept syncing over 41 minutes. A buzz that lands during a Google Health sync can take up to about 6 s.
- **The wrong key.** Every new Golden Gate stack is first offered a key AirBuzz doesn't have (`-10601`). Keeping the same stack up lets the band switch to `BOOTSTRAP`; restarting it fails every time.
- **The fast-link request** (`CONNECTION_PRIORITY_HIGH`) goes out at once on the open link, but on a new connection only after AirBuzz's own session exists, so it can't help Google Health win the handshake race.

Privacy: AirBuzz reads notifications to recognise calls and messages. It keeps the app, the caller or chat name, and what it did, for a week, on the phone. It never stores message text and has no internet permission.

## Build

```
git submodule update --init third_party/golden-gate
git -C third_party/golden-gate submodule update --init external/lwip external/mbedtls
echo "sdk.dir=$ANDROID_HOME" > local.properties   # Android SDK with NDK 27.2.12479018 and CMake 3.22.1
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Licence

GPL-3.0. See `LICENSE` and `THIRD_PARTY_NOTICES.md`.
