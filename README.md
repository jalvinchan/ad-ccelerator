# YouTube Ad Accelerator for Android

A single-tab Android browser powered by Mozilla GeckoView, with a bundled
WebExtension that temporarily accelerates detected YouTube ads. Playback and
acceleration run inside the same app and browser profile. Firefox does not need
to be installed. This replaces the Android WebView prototype in commit `a117335`.

**Experimental:** 0.4.1 passes controlled emulator acceptance. Earlier phone
captures observed accelerated ads, but a repeatable improvement in live ad
wall-clock duration and a fix for the subsequent loading gap remain unverified.
The signed-out emulator can still encounter YouTube’s bot-verification prompt.
Google’s sign-in form opens in-app; completion has not been tested.
See [verification and remaining checks](docs/VERIFICATION.md).

Normal mode has no native toolbar: use YouTube’s Home navigation and pull down
from the top of the page to reload. A Reload button appears for page/browser
errors. A small speed chip sits near the video’s upper-right corner on open
videos, follows playback controls, and fades during playback. Ad acceleration
is always on; routine status banners remain hidden.
Fullscreen overrides portrait lock and restores the previous orientation policy
on exit. Normal and fullscreen selectors offer 0.25× through 4×, including
2.25×. Each new video starts at 1×; the current selection survives ads, rotation, and
background/resume. Selecting during an ad changes the rate restored afterward.
YouTube provides its own search suggestions and history; the app adds no separate
search history. HTTPS links remain in the app, including sign-in and help pages.
New-window links open in the same tab. The extension runs only on exact HTTPS
`m.youtube.com` and `www.youtube.com` origins.

The shared controller tries 16×, 8×, then 4× during detected ads and restores the
selected content speed afterward. It changes only playbackRate: no seeking,
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
Gradle packages the manifest and four runtime scripts from `src/` under
`assets/accelerator/`. Edit those source files, not generated assets. When changing a bundled extension, increase its manifest
version: GeckoView persists it and `ensureBuiltIn` replaces it only when the
version changes.

APKs are generated separately for each CPU architecture under
`app/build/outputs/apk/debug/`. For the Samsung phone and ARM64 emulator:

```sh
adb -s <device-id> install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s <device-id> shell am start -n lab.youtube.accelerator/.MainActivity
```

The ARM64 debug APK is approximately 188 MiB because it includes the browser
engine. APK updates retain the browser profile and remove retired prototype
preferences. GeckoView creates its own browser profile; old WebView and
external-browser cookies are not imported.
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
it temporarily changes rotation settings and backgrounds the app. The fixture and
test harness are not packaged in the APK.
Emulator recording and accessibility automation have repeatability limits;
see the verification notes before interpreting a harness failure or success.
The harness saves/restores both device rotation settings. Current results and
remaining live checks are in [docs/VERIFICATION.md](docs/VERIFICATION.md).

For live acceptance, use a non-Premium session that permits playback and observe
an actual ad. Verify the mobile ad signal, measured media time and shorter ad
wall-clock duration, then speed restoration. A successful rate assignment or
controlled fixture does not establish real YouTube ad acceleration.

## Status and backlog

Current app/extension: **0.4.1**, Android versionCode **7**.

| Item | Status |
|---|---|
| Both landscape fullscreen directions; restore prior orientation | Implemented; earlier phone checks passed |
| Per-video speed, ad/lifecycle safeguards, including 2.25× | Implemented; controlled emulator checks passed |
| No normal toolbar; top-only refresh, error Reload, video speed chip | Implemented; physical-phone/live layout checks pending |
| Native search history | Dropped; YouTube owns search/history |
| Video bookmarks | Deferred; no additional feature is queued |

Keep mobile YouTube and GeckoView. Automatic Skip, desktop mode, release
publishing and further ad-delay investigation are outside the current scope.

## Documentation

- [Privacy](PRIVACY.md): data, permissions and sharing the APK.
- [Architecture](docs/BROWSER_ARCHITECTURE.md): ownership, trust boundaries and lifecycle.
- [Verification](docs/VERIFICATION.md): current proof and remaining acceptance.
- [History](docs/HISTORY.md): earlier engine/phone results and preserved ad-transition findings.

Keep setup, user behavior and backlog here; update current proof in Verification
and append dated historical findings to History when they are superseded.
