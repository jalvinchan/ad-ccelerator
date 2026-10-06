# YouTube Ad Accelerator for Android

A single-tab Android browser powered by Mozilla GeckoView, with a bundled
WebExtension that temporarily accelerates detected YouTube ads. Playback and
acceleration run inside the same app and browser profile. Firefox does not need
to be installed. This replaces the Android WebView prototype in commit `a117335`.

**Experimental: real YouTube ad acceleration is not yet established.** The
unsigned-in emulator session still encounters YouTube's bot-verification prompt.
The Google sign-in form opens inside this browser, but completing sign-in and
playing YouTube videos afterward have not been tested. Changing engines does not
guarantee that YouTube will allow playback. See [docs/VERIFICATION.md](docs/VERIFICATION.md).

The native controls provide Home, Reload, an HTTPS address field, a saved
acceleration switch, status, fullscreen, and Back navigation. HTTPS links remain
in the app, including Google sign-in and help pages. New-window links open in the
same tab. The extension runs only on exact HTTPS `m.youtube.com` and
`www.youtube.com` origins; other websites never receive the accelerator.

The shared controller tries 16×, 8×, then 4× during detected ads and restores the
observed content speed afterward. It changes only playbackRate: no seeking,
muting, Skip clicks, ad blocking, downloads, or official YouTube app automation.
Backgrounding restores speed and pauses video; returning does not call play().

## Build and install

Requires Android 8.0 (API 26) or newer on ARM64, ARMv7, or x86_64. GeckoView's
current stable package does not include a 32-bit x86 engine. Build with Android
Studio's bundled JDK 25, Android compile SDK 37.1, Gradle 9.6.0, and AGP 9.4.1.
Keep your local SDK path in uncommitted `local.properties`.

```sh
node --test test/*.test.cjs
./gradlew assembleDebug lintDebug testDebugUnitTest
```

The engine version is pinned in `gradle/libs.versions.toml`. The first build needs
network access to Mozilla's Maven repository and the transitive dependencies.
Gradle packages `src/manifest.json`, `src/gecko-host.js`, and the two shared
controller scripts under `assets/accelerator/`. Edit those source files, not the
generated assets. When changing a bundled extension, increase its manifest
version: GeckoView persists it and `ensureBuiltIn` replaces it only when the
version changes.

APKs are generated separately for each CPU architecture under
`app/build/outputs/apk/debug/`. For the Samsung phone and ARM64 emulator:

```sh
adb -s <device-id> install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s <device-id> shell am start -n lab.youtube.accelerator/.MainActivity
```

The ARM64 debug APK is approximately 188 MiB because it includes the browser
engine. APK updates retain the acceleration preference. GeckoView creates its
own browser profile; old WebView and external-browser cookies are not imported.
Release signing and distribution remain future work.

## Browser acceptance

With the debug app running in a test emulator/device (Node 22+):

```sh
adb -s <device-id> forward tcp:6000 localabstract:lab.youtube.accelerator/firefox-debugger-socket
ANDROID_SERIAL=<device-id> node scripts/verify-gecko.mjs
```

Set `ADB=/absolute/path/to/adb` if it is not on PATH. The harness uses Firefox's
local debugging protocol to place a controlled media fixture in an existing
YouTube-origin document. It never injects the accelerator: the APK's installed
extension must detect the fixture and change the actual video speed. It checks
media-time advancement, native controls, pause/restoration, fullscreen/Back, and
same-session HTTPS navigation. It returns to YouTube afterward. Use a test device;
it changes the saved toggle and temporarily backgrounds the app. The fixture and
test harness are not packaged in the APK.
Emulator recording and accessibility automation have repeatability limits;
see the verification notes before interpreting a harness failure or success.

For live acceptance, use a non-Premium session that permits playback and observe
an actual ad. Verify the mobile ad signal, measured media time and shorter ad
wall-clock duration, then speed restoration. A successful rate assignment or
controlled fixture does not establish real YouTube ad acceleration.

Read [PRIVACY.md](PRIVACY.md) before sharing the APK,
[docs/BROWSER_ARCHITECTURE.md](docs/BROWSER_ARCHITECTURE.md) for integration details,
and [docs/ANDROID_APP_HANDOVER.md](docs/ANDROID_APP_HANDOVER.md) for the original
handover. Historical WebView results are retained separately in
[docs/WEBVIEW_VERIFICATION.md](docs/WEBVIEW_VERIFICATION.md).
