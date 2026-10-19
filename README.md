# YouTube Ad Accelerator for Android

A single-tab Android browser powered by Mozilla GeckoView, with a bundled
WebExtension that temporarily accelerates detected YouTube ads. Playback and
acceleration run inside the same app and browser profile. Firefox does not need
to be installed. This replaces the Android WebView prototype in commit `a117335`.

**Experimental:** 0.6.3 adds a separate player layer and mini player. It has
not been tested on a device yet. 0.4.9 passes Samsung normal-video native hold restoration
and both speed-menu checks. Real-ad restoration was observed at 1×. Shorts
hold-to-2× is human-verified. Earlier 0.4.1 passed controlled emulator
acceptance. The user confirms
real ads accelerate and content speed is restored afterward. Quantitative live
ad comparisons and a fix for the earlier loading gap are not claimed.
The signed-out emulator can still encounter YouTube’s bot-verification prompt.
Google’s sign-in form opens in-app; completion has not been tested.

Normal mode has no native toolbar: use YouTube’s Home navigation and pull down
from the top of the page to reload. A Reload button appears for page/browser
errors. Videos open in a player layer above the page you were browsing, as in
YouTube's own app. The page underneath stays exactly as it was, including its
scroll position and feed. Back shrinks the player to a mini player above
YouTube's bottom bar, and the video keeps playing. Drag the mini player
anywhere; it settles in the nearest corner, which is kept across restarts. A
sideways flick toward the other side or a downward flick picks the corner it
points to, and a quick upward flick expands it. A sideways flick toward the
edge it already sits on, or a drag mostly off that edge, closes it. Tap it to expand it, use its top-left button to play or pause, or tap ✕ to close it. A video opened from the player replaces the
current one rather than stacking. A channel or other page opened from the
player loads underneath, and the player shrinks. Shorts stay in the feed.
A circular arrow follows the pull, turns teal and gives haptic feedback
at the release threshold, then spins until loading finishes and animates back.
A small speed chip sits near the video’s upper-right corner on open
videos and shows and hides with YouTube’s own player controls, in normal and
fullscreen modes. Long-press the
normal or fullscreen chip to drag it anywhere within the video; a drop past the
edge snaps back inside. The spot is shared by both modes and kept across restarts. Until the chip is moved, the speed selector shows this tip; afterwards it offers Reset chip position. Both chips are one dark pill: back and forward seek icons around the speed. Back and forward buttons beside the chip jump by 5, 10, 15 or 30 seconds, chosen in the speed selector and kept across restarts; they are disabled during ads, on live streams and on Shorts. YouTube's own double-tap still jumps 10 seconds. A seek tap briefly shows the jump under the chip. The selector is a dark sheet docked to the bottom, with the speed readout between its ±0.05× steps, a Skip section, and Reset/Done at the foot. Ad acceleration
is always on; routine status banners remain hidden.
Fullscreen overrides portrait lock and restores the previous orientation policy
on exit. Normal and fullscreen chips open the same selector: a 0.25×–4× slider
with whole-speed marks and tick lines every 0.25×, an exact readout, and ±0.05× steps. Each release or step
applies at once; Done closes it. Each new normal video starts at 1× once; the current selection survives ads, rotation, and
background/resume. Selecting during an ad changes the rate restored afterward.
YouTube menu selections also become the remembered speed. Native hold-to-2×
temporarily yields to YouTube and restores the remembered selection after release.
Shorts and homepage previews receive no default content-speed writes; Shorts have
no app selector and YouTube owns their content speed. Ad restoration still applies.
YouTube provides its own search suggestions and history; the app adds no separate
search history. HTTPS links remain in the app, including sign-in and help pages.
New-window links open in the matching layer; there are no tabs. The extension runs only on exact HTTPS
`m.youtube.com` and `www.youtube.com` origins.

The shared controller tries 16×, 8×, then 4× during detected ads and restores the
selected content speed afterward. It changes only playbackRate during ads: no seeking,
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
treat a single harness failure or success as indicative, not conclusive.
The harness saves/restores both device rotation settings.

For live acceptance, use a non-Premium session that permits playback and observe
an actual ad. Verify the mobile ad signal, measured media time and shorter ad
wall-clock duration, then speed restoration. A successful rate assignment or
controlled fixture does not establish real YouTube ad acceleration.

## Current status

Current app/extension: **0.4.9**, Android versionCode **15**.

| Item | Status |
|---|---|
| Both landscape fullscreen directions; restore prior orientation | Implemented; earlier phone checks passed |
| Per-video speed in 0.05× steps, ad/lifecycle safeguards | Implemented; remembered speed is separate from temporary native holds |
| Draggable speed chip, slider selector, chip follows YouTube's controls | Implemented; user-checked on the Samsung, device harness not yet re-run |
| No normal toolbar; top-only refresh, error Reload, video speed chip | User confirms original behavior; refresh animation polish passes emulator checks |
| Native search history | Dropped; YouTube owns search/history |
| Cooperative content-speed ownership | Mobile-overlay recognition corrected in 0.4.3 |

Upcoming work is listed separately in [BACKLOG.md](BACKLOG.md).

Keep mobile YouTube and GeckoView. Automatic Skip, desktop mode, release
publishing and further ad-delay investigation are outside the current scope.
Older-Android compatibility and destructive recovery acceptance are not required
for this personal app; existing platform safeguards remain in place.

## Documentation

- [Backlog](BACKLOG.md): upcoming work and outstanding acceptance, in suggested order.
- [Privacy](PRIVACY.md): data, permissions and sharing the APK.

Keep setup and user behavior here and upcoming work in Backlog.
