# Inspection Browser for Android

A web browser for Android with full developer tools built in: inspect, debug and
test any site right on the phone. It's built on Android's WebView (Chromium) with Kotlin
and Jetpack Compose.

## Developer tools

**DevTools in every page** (the 🐞 button, or ⋮ ▸ DevTools… to jump to a tool), powered by
[Eruda](https://github.com/liriliri/eruda). They're bundled in the app (no network needed) and
loaded *before the page's own scripts*, so nothing from the first line on is missed:

- **Console**: every log, warning and uncaught error from page start, plus a JavaScript prompt that runs in the page
- **Elements**: the live DOM tree, element picker, computed styles, box model, attributes and event listeners
- **Network**: fetch / XHR with headers, timing and response bodies
- **Resources**: localStorage, sessionStorage, cookies, scripts, stylesheets, iframes, images
- **Sources**: page HTML, scripts and styles, with line numbers
- **Info**, **Snippets** (saved scripts to run on any page), and extra tabs: **Code** (a JS editor),
  **Timing** (Navigation and Resource Timing), **Monitor** (FPS and memory), **Features** (browser support),
  **Touches**, **Orientation**, **Benchmark** and **Geolocation** (spoof a position)
- The 🐞 button shows a badge with the number of console errors on the page

**Native inspection tools** that page scripts can't provide:

- **Network requests** (⇅): *every* request the page makes (documents, scripts, CSS, images, fonts, media, fetch/XHR, iframes),
  with type filters, a failed-only filter, URL search and "Preserve log". Open one to see its URL, query string and request headers,
  **Copy as cURL**, or **Fetch response** to see the real status, response headers, redirects and body
- **View page source**: the source as the server sent it (re-fetched with the page's cookies and user agent),
  the **live DOM** after scripts ran, or the response headers, highlighted, searchable, wrap or scroll, copy and share
- **Cookies**: all of the site's cookies, *including HttpOnly ones*, with add and delete
- **Page info**: TLS certificate, viewport, device pixel ratio, colour scheme, connection, the meta viewport, element counts,
  the user agent actually sent, and the WebView version
- **Remote debugging**: full desktop Chrome DevTools over USB. Turn on USB debugging on the phone, plug it into a computer, and
  open `chrome://inspect` in Chrome there (on by default in Settings)

**Testing options** (Settings):

- User agent: Chrome on Android, raw WebView, desktop Chrome (Linux / Windows), Safari on Mac / iPhone / iPad, Firefox, or your own
- Desktop site switch (⋮ menu), disable cache, JavaScript on/off, third-party cookies, text size, darken pages in dark mode
- Clear cache, cookies, site storage and history
- Certificate errors (self-signed dev servers, intercepting proxies) can be accepted page by page; user-installed CAs are trusted
- Plain `http://` works, and `localhost`, LAN IPs and `.local` names typed in the address bar open over http

## Browser

- Address bar that searches or opens URLs (Google, DuckDuckGo, Bing, Brave or Startpage), with suggestions from bookmarks and history
- Back / forward / reload / stop, find in page, share, bookmarks, history
- Downloads (to Downloads, with the page's cookies), file uploads, camera / microphone / location for pages (asked per page),
  fullscreen video, `intent:` and app links
- Can be set as the default browser and opens links shared from other apps
- Recovers by itself if a page crashes the renderer
- Updates itself from this repository's GitHub releases (Settings ▸ Updates)

## Install on your phone

Every push builds a signed APK in GitHub Actions and publishes it as a release:

1. Open the repository's **Releases** (or **Actions** ▸ latest **Build APK** run ▸ **Inspection-Browser-apk**).
2. Download `Inspection-Browser.apk` and open it on your phone (allow "Install unknown apps" when asked).

After that the app updates itself. The APK is signed with the development key in `keystore/`, so newer builds install
over older ones; it's fine for personal use, not for the Play Store.

## Building locally

Requires JDK 17 and the Android SDK (Android Studio works out of the box).

```
./gradlew testReleaseUnitTest assembleRelease
```

The DevTools bundles live in `app/src/main/assets/devtools/` (Eruda 3.4.3 and plugins, from npm); `boot.js` there loads them.

## Limits

- WebView only reports response status codes for errors (400 and up) to the request log; use **Fetch response** to see the rest.
- Request bodies (POST data) aren't visible to the native log; Eruda's Network tab shows them for fetch / XHR.
- Pages with a strict Content-Security-Policy on styles can stop Eruda from drawing itself; remote debugging always works.
