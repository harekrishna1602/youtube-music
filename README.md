# YT Music BG v1.2.0 — Built by Studio Mogli

Developed and maintained by **Studio Mogli** ([studiomogli.com](https://studiomogli.com)).

A dedicated Android wrapper app locked exclusively to **YouTube Music (`music.youtube.com`)** featuring screen-off background playback, a 3-layer ad blocker, isolated Google Sign-In, and Studio Mogli developer credits.

## What's New in v1.2.0
- **Isolated Google Sign-In (`LoginActivity.kt`):** Routes `accounts.google.com` into a completely clean WebView with zero injected JS, zero `JavascriptInterface`, zero ad-blocker hooks, and the device's real Android Chrome User-Agent (stripped of `; wv` and `Version/4.0`) to resolve Google's *"This browser or app may not be secure"* error.
- **Dynamic Engine-Matched Desktop UA:** Builds the YouTube Music player User-Agent dynamically from the phone's actual Chromium WebView version instead of a hardcoded outdated Chrome string.
- **Studio Mogli Developer Credits & Support:** Adds a slim charcoal/orange top strip (`YT MUSIC BG • STUDIO MOGLI`) with quick **Sign In** and **Studio Mogli** buttons opening a native credits & developer support dialog linked to `https://studiomogli.com`.

---

## Build the Installable APK via GitHub Actions

1. Unzip `YTMusicBG-Android-v3.zip`.
2. Update your GitHub repository's `main` branch with the new files (remember `.github/workflows/build-apk.yml` if uploading via browser).
3. Wait ~2–3 minutes in the **Actions** tab and download **YTMusicBG-v1.2.0-debug-apk**.
4. Extract `app-debug.apk` and install it on your phone.
