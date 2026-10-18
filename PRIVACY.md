# YouTube Ad Accelerator privacy

This Android app embeds Mozilla's GeckoView browser engine. It loads YouTube and
other HTTPS sites inside its own browser profile. Websites and their supporting
media, advertising, and analytics services receive network requests, your IP
address, and information associated with browsing, account sign-in, and playback.
Their processing is governed by their respective privacy policies. This is not
an app running inside the official YouTube app.

GeckoView stores cookies, website storage, cache, and its installed extension
locally. First-party cookies are accepted; third-party cookies are isolated by
site, using GeckoView's browser setting. External browsers and the old WebView
have separate storage; their cookies are not copied into GeckoView. The
app does not store a separate search history or acceleration preference. It
stores the speed chip's screen position, and while Android may recreate the app
it keeps the browser's tab history in a private app file that is deleted once
restored. YouTube owns its search UI and history within the browser profile.
Retired prototype
search-history and toggle preferences are removed on startup. Content-speed
selection stays in document memory
and resets on a new video or fresh load. Uninstalling or clearing app storage
removes local data. Cloud backup and device transfer of app data are excluded.

Google's sign-in pages can open inside this browser. Account sign-in has not
been tested to completion and may still be restricted by Google. Credentials
are entered into Google's page; the accelerator is not loaded on Google account
pages. The app does not implement its own account service or cookie transfer.

The bundled accelerator runs only on exact HTTPS mobile/desktop YouTube origins.
It changes playbackRate during detected ads and restores content speed; it does
not click ads or Skip, seek, mute, or change volume. A privileged, isolated
extension channel exchanges lifecycle state and validated context-bound
content-speed commands/status with the native UI. No page-accessible native
JavaScript bridge is exposed. Up to 30
controller diagnostics remain in extension memory until the document is replaced.
Debug builds enable local Firefox inspection and console logs; release builds do
not. Review device logs before sharing them.

The app adds no telemetry collection or remote diagnostics endpoint. GeckoView
is a bundled third-party browser engine and may make its own service requests,
for example for browser security functions. This statement does not claim that
website traffic or the engine is offline.

The merged APK declares Internet, network-state, wake-lock, and audio-settings
permissions supplied by the browser engine, plus AndroidX's app-specific internal
receiver permission. No camera, microphone, location, or contacts permission is
requested. Site permission requests are denied. The native lifecycle pauses media
when the app is backgrounded.

This is an independent experimental app, not a Mozilla, Google, or YouTube product.
