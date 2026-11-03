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
  **Touches**, **Orientation** and **Benchmark** (these extra tabs load the first time DevTools are opened on a page, so pages load faster)
- The 🐞 button shows a badge with the number of console errors on the page

**Native inspection tools** that page scripts can't provide:

- **Network requests** (⇅): *every* request the page makes (documents, scripts, CSS, images, fonts, media, fetch/XHR, iframes),
  with type filters, a failed-only filter, URL search and "Preserve log". Open one to see its URL, query string and request headers,
  **Copy as cURL**, or **Fetch response** to see the real status, response headers, redirects and body
- **View page source**: the source as the server sent it (re-fetched with the page's cookies and user agent),
  the **live DOM** after scripts ran, or the response headers, highlighted, searchable, wrap or scroll, copy and share
- **Cookies**: all of the site's cookies, *including HttpOnly ones*, with add and delete
- **Page speed** (⋮ ▸ Page speed): server response, first and largest contentful paint, layout shift, load time,
  page weight and request count, rated against the usual web-vitals thresholds; downloads by type, and the largest
  and slowest files
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

- Tabs: the tab button shows how many are open and lists them to switch or close; ⋮ ▸ New tab; long-press a link or
  image to open it in a new or background tab, copy, share or download it. Links that open a new window (`target=_blank`,
  `window.open`) get their own tab, and Back from one returns to the page that opened it. Each tab has its own history,
  request log and DevTools, and the tabs come back when the app is reopened (loading only when you open them)
- Tab list as cards with a picture of each page
- Private tabs (⋮ ▸ New private tab, or long-press a link): no history, not saved with the other tabs, and their own
  cookies and storage, wiped when the last private tab closes (on older WebViews they only skip history)
- QR code of the page's address (⋮ ▸ QR code) to open it on another device
- Pull down to refresh at the top of a page (not on scrolled boxes inside it or the DevTools panel; can be switched off)
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

The DevTools bundles live in `app/src/main/assets/devtools/` (Eruda 3.4.3 and plugins, from npm; not eruda-geolocation, which loads Leaflet into every page); `boot.js` there loads them.

## Limits

- WebView only reports response status codes for errors (400 and up) to the request log; use **Fetch response** to see the rest.
- Request bodies (POST data) aren't visible to the native log; Eruda's Network tab shows them for fetch / XHR.
- Pages with a strict Content-Security-Policy on styles can stop Eruda from drawing itself; remote debugging always works.
