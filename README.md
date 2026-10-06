# YouTube Ad Accelerator for Android

**Prototype: real YouTube playback and ad acceleration are not yet verified.**
The tested YouTube session stops at a bot-verification prompt. Opening a video
in an external browser cannot run this app's accelerator and does not meet the
core goal. See [docs/VERIFICATION.md](docs/VERIFICATION.md) for the evidence.

A standalone Android WebView app for YouTube’s mobile site. It temporarily tries
16×, 8×, then 4× playback during detected ads and restores the content’s observed
speed afterward. It includes Home, Reload, Browser, a saved acceleration toggle, player
status, fullscreen video, and Back navigation. Backgrounding restores speed and
pauses videos; returning does not explicitly resume playback.

Requires Android 8.0 (API 26) or newer. No root, Accessibility service, ad blocking,
automatic Skip clicks, downloads, background playback, or account support.
Live YouTube compatibility must be established separately from unit tests.

## Build and test

Open this folder in Android Studio. Use the bundled JDK 25, Android SDK platform
37, and the checked-in Gradle 9.6.0 wrapper (Android Gradle plugin 9.4.1).
Set your local SDK location in `local.properties`; do not commit that file.

```sh
node --test test/*.test.cjs
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Node 18+ is sufficient; JavaScript tests have no npm dependencies. The Android
runtime uses platform Java/widgets/WebView and adds no app runtime dependencies.
Gradle generates assets from `src/ad-session.js` and `src/page-controller.js`;
edit those sources, not the generated copies. No Chrome adapter is packaged.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n lab.youtube.accelerator/.MainActivity
```

Debug signing is for local testing. Release signing and distribution are future
work. Read [PRIVACY.md](PRIVACY.md) before sharing the app and
[docs/VERIFICATION.md](docs/VERIFICATION.md) for what has actually been verified.
The source handover is retained in [docs/ANDROID_APP_HANDOVER.md](docs/ANDROID_APP_HANDOVER.md).

## Automated WebView acceptance

For a running debug build on a test emulator/device, forward its debug WebView
socket, then run the controlled media check (Node 22+). Replace `<app-pid>` with
`adb shell pidof lab.youtube.accelerator`:

```sh
adb forward tcp:9222 localabstract:webview_devtools_remote_<app-pid>
node scripts/verify-webview.mjs
# Focus only on native external-link and browser actions:
node scripts/verify-webview.mjs --links-only
```

`ADB=/absolute/path/to/adb` can select the SDK executable if it is not on PATH.
Set `ANDROID_SERIAL` to the test device ID when more than one device is connected.
The harness intercepts one test URL in the local inspector and serves a fixture;
it does not add any fixture or test bypass to the APK. It records video locally,
checks real media-time advancement, uses the native switch and Android Home/Back,
and returns to YouTube afterward. Use a test device: it changes the saved toggle
and temporarily backgrounds the app. This establishes WebView/controller behavior,
not YouTube ad compatibility.

## Device acceptance

Use a non-Premium YouTube session, start a video by tapping its player, and watch
for an actual ad. Confirm `.html5-video-player.ad-showing` corresponds to that ad
and that media time advances faster than wall-clock time. Compare real ad duration
with acceleration disabled. A successful playback-rate assignment alone is not
proof of a shorter ad.

Choose a content speed, then check restoration after ad completion, disabling,
Home/Reload/navigation, player replacement, app pause, and fullscreen/Back.
Check rotation and recreation, narrow screens, errors, and external links.
In debug builds Android Studio or Chrome remote inspection can read
`window.YouTubeAdAccelerator.getStatus()` (including the latest 30 diagnostics).
If YouTube shows “Sign in to confirm you’re not a bot”, it is withholding
playback from this WebView session. The app cannot remove that requirement.
Use the native Browser button to open the current page in a browser. Browser
sign-in cookies are separate from the app; playback there has no app acceleration.
Sign in and Learn more links also leave the app. External redirects without a
WebView gesture offer an explicit Open browser confirmation rather than silently
being discarded. Native and JavaScript guards restrict injection to HTTPS `m.youtube.com` and
`www.youtube.com`. If YouTube changes its markup, change the controller only after
inspecting the mobile page and add a focused regression test.
