# YouTube Ad Accelerator privacy

This standalone Android app loads YouTube’s mobile website in Android WebView.
YouTube and its supporting media, advertising, and analytics services receive
network requests, your IP address, and information associated with browsing and
playback. Their processing is governed by Google’s and YouTube’s privacy policies.
The app does not operate inside the official YouTube app.

WebView stores first-party cookies, website storage, and cache on the device.
Third-party cookies are disabled. The acceleration preference is stored locally.
Uninstalling the app or clearing its storage removes its locally stored data.
Cloud backup and device transfer of app data are excluded.

The accelerator changes only playback speed while the selected player signals
an ad. It does not click ads or Skip, seek, mute, or alter volume. The app itself
has no telemetry, analytics SDK, remote diagnostics service, or native JavaScript
bridge. Up to 30 diagnostic events are kept in page memory until a new document
loads; debug builds expose WebView inspection to local development tools. Events
may also appear in local device logs. Do not share logs without reviewing them.

Only the Android Internet permission is declared; there are no runtime permission
prompts. User-initiated external HTTPS links open in a browser, which has its own
storage and privacy policy. Account sign-in is not supported as a first-version
feature, and Google may reject sign-in in an embedded WebView.

This is an independent experimental app, not a Google or YouTube product.
