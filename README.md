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
- **0.5.6** (versionCode 18): problem notifications while AirBuzz is closed (brief decision 41, "quiet, then loud"). One notification, updated in place: when Bluetooth turns off while AirBuzz is on and not paused, a silent "Bluetooth is off" with Turn on; when a call or message still doesn't reach the Air after its retries, it turns loud ("Mum called · your Air didn't ring", why, and the fix when there is one), and a second miss reads "2 alerts didn't reach your Air". It clears when Bluetooth is back or a buzz gets through, and never shows while AirBuzz is off or paused. A message still retries for up to 30 s, but not once the phone is unlocked or the notification dismissed (logged as seen on the phone).
- **0.5.7** (versionCode 19): one page change everywhere, modelled on Linear's app, replacing the shelf's Expand reveal. A deeper page (Settings, Activity, Appearance, Setup checklist, Test) starts 40 % of the width to the right and settles while it fades in, so only the end of its slide shows; the page you leave drifts 15 % the other way and fades out sooner. Back mirrors it, a little faster (360 ms in, 300 ms back).
- **0.5.8** (versionCode 20): the shelf opens Settings as a sheet that rises from the bottom over the dimmed Home (300 ms in, 220 ms back); Appearance, the setup checklist and Test change like pages inside it, and Settings closes with a down chevron, Back, a tap on the dim or a swipe down. The pickers use the same sheet, so they rise and close faster than Android's, and swipe down the same way. Onboarding steps change like pages. The apps picker no longer freezes AirBuzz while it reads the app list (1.9 s on the first open, 0.5 s after).
- **0.5.9** (versionCode 21): AirBuzz opens its listen-only link to the Air as soon as it starts, when it is switched on, when Bluetooth comes back on and again after the link drops (waiting 5 s, then longer, up to 30 min), so the first alert doesn't need a new connection. On 0.5.6 a Telegram message that had to connect afresh took 8.2 s. The step log also records the Air's and the phone's battery once an hour, to measure what the open link costs over a day.
- **0.6** (versionCode 22): calls and quiet times split (brief decision 42). Quiet times and Pause now silence chosen people too; Stay quiet has "Let some people ring anyway", off and empty at first, so after updating Bruna doesn't ring at night until you add her there. Who can ring lists people only in Chosen; Contacts and Anyone explain themselves in one sentence; both end in a note that opens Stay quiet. A bin removes a person at once and leaves Undo for 5 s; Called recently has an × to hide a number. The Messages and Calls blanks show whole names, then "and N more", then "N apps", and never cut a name.
- **0.6.1** (versionCode 23): removing a person is easier to see: the row slides out to the left and a tinted Undo row takes its place, whose Undo button empties over 5 s. Each removal keeps its own 5 s, so removing a second person no longer cuts the first one's Undo short, and an expired row folds away instead of jumping. "Add people" sits at the top of each list and is 56 dp tall. The On / Paused / Off word next to the Home switch is set in Geist Pixel at 24 sp, like the big numbers.
- **0.6.2** (versionCode 24): the greys take a hint of the chosen accent, so each accent feels like its own theme while the background still reads as grey; white cards stay white in light mode (brief decision 44). Green (light) and Lime (dark) are the new defaults and come first in Appearance; they swapped places with Orange and Turquoise, so anyone who had picked one of those now sees the other.
- **0.6.3** (versionCode 25): new app icon, the Air's pebble with two Lime tap rings on near-black, with a matching themed (monochrome) icon and notification icon (brief decision 45). The 512 px Play Store icon is `store/play-icon-512.png`.
- **0.6.4** (versionCode 26): Settings has a Missed alerts row under Appearance. It shows how that notification arrives (Pop-up with sound, Sound, Silent or Off) and opens Android's page for it (issue #2).
- **0.6.5** (versionCode 27): once a problem has cost an alert, the Air shelf's second line says how many, such as "1 missed since 13:58", in the warning colour (issue #3). The light warning colour is a little darker (#A35E00), so its text keeps 4.5:1 on white; `ThemeTest` now checks it.

## What it does

- **Calls**, from any app that rings (phone, WhatsApp, Signal…): the Air buzzes every second until you answer, decline or press Silence, for up to 60 s. Who can ring is Chosen people (the default), Contacts, or Anyone. Quiet times and Pause silence calls too, except from people in Stay quiet's "Let some people ring anyway" list, which is off and empty until you fill it.
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
| `service/` | `AlertListener` (a notification listener) recognises incoming calls and messages. Android keeps a notification listener running, so there is no foreground service or permanent notification; the notifications are "Ringing your Air" with Silence during a call, and one problem notification: silent while Bluetooth is off, loud once a call or message didn't reach the Air (brief decision 41). |
| `data/` | Settings and the week's activity log, kept on the phone only. |
| `ui/` | Jetpack Compose screens in the approved look (design pass in Paper, brief decisions 20–39): rules as sentences with tinted blanks that each open one bottom sheet. `Theme.kt` holds the colour and type tokens; `Onboarding.kt` is the first-launch setup. |
| `Engine.kt` | Connects the two: one queue of band commands, where a call goes ahead of messages. |

Choices that came from measuring on a real Air (test app 0.2–0.4 in the private research repo):
- **The open link.** A listen-only Bluetooth client stays connected between buzzes; the secure session is still closed and handed back to Google Health after each one. That took a message buzz from a 3.1 s median to 0.5 s, and Google Health kept syncing over 41 minutes. A buzz that lands during a Google Health sync can take up to about 6 s.
- **The wrong key.** Every new Golden Gate stack is first offered a key AirBuzz doesn't have (`-10601`). Keeping the same stack up lets the band switch to `BOOTSTRAP`; restarting it fails every time.
- **The fast-link request** (`CONNECTION_PRIORITY_HIGH`) goes out at once on the open link, but on a new connection only after AirBuzz's own session exists, so it can't help Google Health win the handshake race.

Privacy: AirBuzz reads notifications to recognise calls and messages. It keeps the app, the caller or chat name, and what it did, for a week, on the phone. Its step log (shared only when you press Share log) names apps and outcomes, never message text or callers, and also keeps one week. It never stores message text and has no internet permission.

## Build

```
git submodule update --init third_party/golden-gate
git -C third_party/golden-gate submodule update --init external/lwip external/mbedtls
echo "sdk.dir=$ANDROID_HOME" > local.properties   # Android SDK with NDK 27.2.12479018 and CMake 3.22.1
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Releases: push a tag `v<versionName>`, such as `v0.7.0`. GitHub Actions (`.github/workflows/release.yml`) runs the tests, builds an APK signed with the app signing key and a Play bundle signed with the upload key, and puts the APK, its SHA-256 and the signing certificate's SHA-256 on a draft GitHub Release. The keys come from the `AIRBUZZ_KEYSTORE_B64` and `AIRBUZZ_KEYSTORE_PASSWORD` secrets; without them, `app/build.gradle.kts` builds exactly as before.

## Licence

GPL-3.0. See `LICENSE` and `THIRD_PARTY_NOTICES.md`.
