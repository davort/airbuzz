# Third-party notices

AirBuzz is licensed under the GNU General Public License v3.0 (see `LICENSE`), with the additional terms in `NOTICE.md`. It includes or builds on the following, under their own licences. The full texts are in `licenses/`, and the app carries all of them, this file and `LICENSE` as assets under `legal/`.

| Component | Where | Licence |
|---|---|---|
| fitbit-air by Thore Koritzius (github.com/ThoreKoritzius/fitbit-air, commit 5f76f5f) | The band protocol in `app/src/main/java/com/davort/airbuzz/band/` follows this library: the tracker channel, the quiet wait and its values, the handshake and hand-off with a key resolver that refuses every key, the open link, the `/settings` haptic payloads, the battery resource `/md/606` with its field numbers, the layouts of the link-status characteristics `ABBAFD01` and `ABBAFD02`, and retrying a Bluetooth write Android declines as busy (AirBuzz's decoders and code are its own). | MIT, `licenses/fitbit-air-MIT.txt` |
| Golden Gate by Fitbit (github.com/Fitbit/golden-gate, commit b8a6f25) | Git submodule in `third_party/golden-gate`, built into `libgg.so` and `libxp.so`, plus its Kotlin bindings. `goldengate/src/main/kotlin/.../Stack.kt` is modified (an optional TLS key resolver) and says so in its header. | Apache-2.0, `licenses/Apache-2.0.txt`; its licence header `licenses/golden-gate-Apache-2.0.txt` and `licenses/golden-gate-NOTICE.txt` |
| Mbed TLS (Golden Gate submodule) | Statically linked into `libgg.so` | Apache-2.0, `licenses/mbedtls-LICENSE.txt` |
| lwIP (Golden Gate submodule) | Statically linked into `libgg.so` | BSD 3-Clause, `licenses/lwip-BSD.txt` |
| libphonenumber by Google | Phone number matching | Apache-2.0, `licenses/Apache-2.0.txt` |
| AndroidX, Jetpack Compose, RxJava | App framework | Apache-2.0, `licenses/Apache-2.0.txt` |
| OkHttp and Okio (Square), Timber (Jake Wharton), RxAndroid, AndroidX Annotation | Libraries the Golden Gate bindings in `goldengate/` depend on | Apache-2.0, `licenses/Apache-2.0.txt` |
| Google Sans Flex, Google Sans Code (google/fonts) | `app/src/main/res/font/google_sans_flex.ttf`, `google_sans_code.ttf` | SIL Open Font License 1.1, `licenses/GoogleSansFlex-OFL.txt`, `licenses/GoogleSansCode-OFL.txt` |
| Geist Pixel Square by Vercel (vercel/geist-font) | `app/src/main/res/font/geist_pixel.ttf` | SIL Open Font License 1.1, `licenses/GeistPixel-OFL.txt` |

No code or binary from the Google Health or Fitbit apps is included.

Google, Fitbit and Fitbit Air are trademarks of Google LLC. AirBuzz is not made by, endorsed by or affiliated with Google or Fitbit.
