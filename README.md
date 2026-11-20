# YT Music BG v1.1.0 (Screen-Off Playback + 3-Layer Ad Blocker)

A dedicated Android wrapper app locked exclusively to **YouTube Music (`music.youtube.com`)** that keeps audio playing when the screen is off and strips/skips audio and visual ads automatically.

## Features
- **Background & Screen-Off Playback:** `BackgroundWebView` + Android Foreground Service (`mediaPlayback`) + `PARTIAL_WAKE_LOCK` + `MediaSessionCompat` lock-screen and Bluetooth controls.
- **Layer 1 Ad Blocker (`AdBlocker.kt`):** Intercepts and drops ad servers and ad-tracking beacons inside `WebViewClient.shouldInterceptRequest` while keeping `googlevideo.com`, `/youtubei/v1/player`, and Google Sign-In intact.
- **Layer 2 Ad Blocker (`JsScripts.DOCUMENT_START_SCRIPT`):** Registered via `WebViewCompat.addDocumentStartJavaScript` before page initialization to prune `adPlacements`, `playerAds`, `adSlots`, and `adBreakHeartbeatParams` from `window.ytInitialPlayerResponse`, `fetch()`, and `XMLHttpRequest`.
- **Layer 3 Ad Blocker (`JsScripts.RUNTIME_AND_COSMETIC_SCRIPT`):** Hides Premium popups and ad overlays via CSS, runs a `MutationObserver` fallback that mutes, seeks past, and clicks skip on any ad that slips through, and prevents ad titles from flashing on the lock screen.

---

## Build the Installable APK via GitHub Actions (No Android Studio Needed)

1. Unzip `YTMusicBG-Android-v2.zip`.
2. Push all files (including `.github/workflows/build-apk.yml`) to a GitHub repository's `main` branch.
3. Open the **Actions** tab and wait ~2-3 minutes for **Build Android APK** to finish.
4. Download the **YTMusicBG-v1.1.0-debug-apk** artifact, unzip it to get `app-debug.apk`, and install it on your Android phone.

## First-Time Phone Setup
1. **Allow Notifications** when prompted so Android 13+ displays lock-screen controls.
2. Go to **Settings > Apps > YT Music BG > Battery** and set it to **Unrestricted**.
