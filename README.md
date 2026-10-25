# Swift Browser

A fast, clean Android web browser built with Kotlin, WebView and Material 3.

**Features:** smart address bar (URL or search), progress bar, pull-to-refresh, bookmarks,
incognito mode, desktop-site mode, downloads, share, fullscreen video, external-app links,
light/dark/system theme, and a full settings screen (search engine, homepage, text size,
JavaScript, pop-up blocking, HTTPS-only, third-party cookies, clear data on exit).

## Build
- **GitHub Actions:** push to `main` → open the *Actions* tab → download the `SwiftBrowser-debug-apk` artifact.
- **Android Studio:** open this folder and press Run.
- **Command line:** `gradle assembleDebug` (Gradle 8.7+, JDK 17).
