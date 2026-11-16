# Kite

An original, one-handed Android launcher built around three ideas:

1. **Letter wheel + locked layout.** Press anywhere on home and slide. An arc of letters opens beside your thumb, on whichever side you're holding the phone. Slide along it and the letters next to your thumb grow; the letter's apps wait further across the screen. Slide across onto one and let go. The wheel learns: the app you open most is straight across from its letter, and letters you use most get more room. Slide back toward the edge to cancel. Pinned apps sit in the thumb zone and never reshuffle. One "now" slot below them changes with the time of day.
2. **Strict social timer.** You get a daily budget for social apps, and raising it only takes effect tomorrow. Each open from Kite has a pause that grows (5, 10, 20, 30 s). Blackout hours lock social apps completely. When you're out of time, a block screen covers the app, however you opened it. The only way through is to wait on that screen, and each wait doubles.
3. **Parking lot.** Set Kite as the phone's digital assistant, then use the assistant gesture in any app. Kite saves a card with the screenshot, the app, and a link to the exact content when the app provides one. Tapping the card takes you straight back. *Share › Park in Kite* works too. Cards expire after 24 hours unless you keep them.

## Install

Every push to `main` builds an APK. On your phone, open the **Releases** page of this repo, find **Latest build**, download `app-debug.apk` and install it. You'll need to allow installs from your browser the first time. Builds from pull requests are under the **Actions** tab, as an artifact.

The first time you open Kite it walks you through the wheel and the permissions. You can replay that, or finish anything you skipped, from the **⋯** menu on home.

## vivo / OriginOS notes

- Turn on **Autostart** and **Unrestricted battery** for Kite. Without them, OriginOS kills the timer in the background.
- The default assistant is set in *Settings › Apps › Default apps › Digital assistant app*.
- After big system updates, vivo can switch the home screen back to its own launcher. Kite shows "Finish setup" on home when that happens.
- Swipe-home animations can look a little rougher with any third-party launcher on vivo. That's the OS, not something Kite can fix.

## Build locally

You need Android Studio (or JDK 17 plus the Android SDK). Run `./gradlew assembleDebug`, and the APK lands in `app/build/outputs/apk/debug/`.

## Layout

| Path | What |
|---|---|
| `ui/HomeScreen.kt` | Home, all apps / search, parking lot row, app menu |
| `ui/LetterWheel.kt` | Letter wheel: geometry, gesture logic, learning |
| `ui/WheelOverlay.kt` | Letter wheel: touch handling, drawing, vibration |
| `ui/Onboarding.kt` | First-run walkthrough |
| `ui/SettingsScreen.kt` | Setup checklist and timer settings |
| `timer/` | Budget rules, watcher service, pause/block screen |
| `capture/` | Assistant capture session and share receiver |
| `data/` | App list, prefs, parking lot storage, permission helpers |
