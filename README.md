# AirBuzz

AirBuzz makes a Google Fitbit Air vibrate for calls from people you choose and messages from apps you choose. Nothing else buzzes.

Works with Google Fitbit Air. AirBuzz is not made by, endorsed by or affiliated with Google or Fitbit. It talks to the Air over an unofficial protocol, so a firmware update could stop it working.

## Versions

- **0.1** (versionCode 1): first build of the real app. Installed on Davor's phone over adb (Play Protect blocks browser installs of apps with a notification listener); it warned that `libgg.so` and `libxp.so` weren't 16 KB-aligned.
- **0.2** (versionCode 2): Golden Gate libraries linked for 16 KB pages; no permanent status notification or foreground service (the notification listener keeps AirBuzz running); Test buzz no longer counts on Home's buzz strip.
- **0.3** (versionCode 3): the approved look from the first design pass (brief decisions 20–32): Google Sans Flex, Google Sans Code, Instrument Serif and Geist Pixel; cool grey palettes; slim segmented buttons; Pause from the Home switch with a paused card; "ignored" alerts; Activity one day at a time with totals and ignored-by-source rows; Test buzz with the front drawing of the Air, a 0–8 s timeline and Show details.
- **0.3.1** (versionCode 4): a buzz that loses the Air to Google Health is tried again every 5 s instead of dropped: messages for up to 30 s, calls for as long as they ring (up to 60 s). 0.2 dropped a Telegram message this way on Davor's phone.
- **0.3.2** (versionCode 5): AirBuzz keeps its own log of its last 500 steps (each call and message with the app and what the rules decided, every band step, retries, the notification listener connecting) and Settings has Share log. The phone's system log keeps only a few minutes. No message text or caller names go in it.
- **0.3.3** (versionCode 6): Settings moved from the Home top bar to the Air shelf, a card floating at the bottom of Home that shows when the Air was last reached and opens Settings (brief decision 33). When something stops AirBuzz, the shelf turns into that one problem with its fix: Bluetooth off, Nearby devices or notification access not allowed, no paired Air, or the Air out of reach. It replaces the problem banner. The Home buzz strip is as tall as the one on Activity (decision 34).
- **0.3.4** (versionCode 7): the Air shelf shows the Settings icon instead of a chevron, and its dot is green only while AirBuzz is on and has reached the Air (grey before, when off, or paused). Switches are filled with the accent colour when on.
- **0.3.5** (versionCode 8): the Pause sheet's last option is "Turn off" instead of "Until I turn it back on": it switches AirBuzz off, so nothing buzzes, not even chosen callers, and Home says Off (brief decision 37). Timed pauses still let chosen callers ring.
- **0.4** (versionCode 9): onboarding on first launch (brief decision 39): seven steps (Welcome, Link your Air, Keep AirBuzz running, Notification access, Who can ring, Which apps, Feel it) that resume until done; existing installs skip it. The pebble drawing of the Air on Test buzz, the Home shelf and onboarding (decision 38). Settings has "Show onboarding again" for testing. Rule blanks: the words share the sentence's baseline and the arrow centres on the lowercase letters.
- **0.4.1** (versionCode 10): onboarding keeps its main button at the bottom of the screen; a long step, such as a long app list, scrolls above it, with a hairline over the button while more is below.
- **0.4.2** (versionCode 11): the step log measures what was still unmeasured, with no change you can see. Each call and message says how long Android took to deliver its notification and when the Air first buzzed after it arrived, including retries; every band command logs its steps (link, quiet wait, handshakes, wrong keys, lost races). Google Health's traffic on the open link is summed every 5 minutes, to find what a sync looks like. Each start says why Android started AirBuzz and why it last stopped it, to see whether the phone ends it in the background. A disconnected notification listener asks Android to reconnect it. The log keeps 1,000 lines instead of 500.
- **0.5** (versionCode 12): motion (brief decision 40). The shelf opens Settings by staying put and becoming its last row while Settings rises in above it (Anchor); the Air row with Test moved to the bottom of Settings. A problem cross-fades into the shelf and its glow flares once. New alerts grow a bar in the strip and open a history row; the On/Paused/Off word rolls and the paused card swaps in place; blanks press in; Test buzz sends one ring out on the tap with a short twitch; onboarding steps slide with Next and Back. All of it follows Android's animation scale, so "Remove animations" turns it off.
- **0.5.1** (versionCode 13): the shelf opens Settings by Expand instead of Anchor: the shelf grows into the whole Settings page over 450 ms while its own content fades out and Settings fades in, and Back shrinks it into the shelf in 300 ms. The shelf shows only on Home again; Settings has its Google Fitbit Air row with Test back in its list (brief decision 40).
- **0.5.2** (versionCode 14): Settings' content fades in (200 ms) only after the shelf has finished growing into the page, and on Back fades out (120 ms) before the page shrinks into the shelf.
- **0.5.3** (versionCode 15): the shelf expands into Settings in 380 ms again, with Settings fading in as it grows as in 0.5.1; only the Settings title waits until the page is full and then fades in (180 ms). Back fades the title out first (100 ms), then shrinks the page into the shelf (260 ms).
- **0.5.4** (versionCode 16): after the shelf has grown into Settings, the Settings title rises from the bottom of the Strength card into its place, coming out from behind the card as it fades in (480 ms); Back sinks it behind the card (220 ms) before the page shrinks into the shelf.
- **0.5.5** (versionCode 17): the shelf opens Settings as one parallax reveal. A single 460 ms timeline grows the panel and brings Settings in layer by layer from the bottom up; each higher layer starts a little later and travels further, so the title rises last and furthest, out from behind the Strength card. Back plays it in reverse in 300 ms.

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
| `ui/` | Jetpack Compose screens in the approved look (design pass in Paper, brief decisions 20–39): rules as sentences with tinted blanks that each open one bottom sheet. `Theme.kt` holds the colour and type tokens; `Onboarding.kt` is the first-launch setup. |
| `Engine.kt` | Connects the two: one queue of band commands, where a call goes ahead of messages. |

Choices that came from measuring on a real Air (test app 0.2–0.4 in the private research repo):
- **The open link.** A listen-only Bluetooth client stays connected between buzzes; the secure session is still closed and handed back to Google Health after each one. That took a message buzz from a 3.1 s median to 0.5 s, and Google Health kept syncing over 41 minutes. A buzz that lands during a Google Health sync can take up to about 6 s.
- **The wrong key.** Every new Golden Gate stack is first offered a key AirBuzz doesn't have (`-10601`). Keeping the same stack up lets the band switch to `BOOTSTRAP`; restarting it fails every time.
- **The fast-link request** (`CONNECTION_PRIORITY_HIGH`) goes out at once on the open link, but on a new connection only after AirBuzz's own session exists, so it can't help Google Health win the handshake race.

Privacy: AirBuzz reads notifications to recognise calls and messages. It keeps the app, the caller or chat name, and what it did, for a week, on the phone. Its step log (shared only when you press Share log) names apps and outcomes, never message text or callers. It never stores message text and has no internet permission.

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
