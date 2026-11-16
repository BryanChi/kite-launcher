# Kite

An original, one-handed Android launcher built around three ideas:

1. **Letter wheel + locked layout.** Press anywhere on home and slide. A fan of letters opens around your thumb. Rest on one and its apps fan out, then let go on the app you want. Pinned apps sit in the thumb zone and never reshuffle. One "now" slot below them changes with the time of day.
2. **Strict social timer.** You get a daily budget for social apps, and raising it only takes effect tomorrow. Each open from Kite has a pause that grows (5, 10, 20, 30 s). Blackout hours lock social apps completely. When you're out of time, a block screen covers the app, however you opened it. The only way through is to wait on that screen, and each wait doubles.
3. **Parking lot.** Set Kite as the phone's digital assistant, then use the assistant gesture in any app. Kite saves a card with the screenshot, the app, and a link to the exact content when the app provides one. Tapping the card takes you straight back. *Share › Park in Kite* works too. Cards expire after 24 hours unless you keep them.

## Install

Every push to `main` builds an APK. On your phone, open the **Releases** page of this repo, find **Latest build**, download `app-debug.apk` and install it. You'll need to allow installs from your browser the first time. Builds from pull requests are under the **Actions** tab, as an artifact.

Then open Kite, tap **Set up ●**, and work through the checklist.

## vivo / OriginOS notes

- Turn on **Autostart** and **Unrestricted battery** for Kite. Without them, OriginOS kills the timer in the background.
- The default assistant is set in *Settings › Apps › Default apps › Digital assistant app*.
- After big system updates, vivo can switch the home screen back to its own launcher. Kite shows "Set up ●" when that happens.
- Swipe-home animations can look a little rougher with any third-party launcher on vivo. That's the OS, not something Kite can fix.

## Build locally

You need Android Studio (or JDK 17 plus the Android SDK). Run `./gradlew assembleDebug`, and the APK lands in `app/build/outputs/apk/debug/`.

## Layout

| Path | What |
|---|---|
| `ui/HomeScreen.kt` | Home, all apps / search, parking lot row, app menu |
| `ui/LetterWheel.kt` | The one-swipe letter wheel |
| `ui/SettingsScreen.kt` | Setup checklist and timer settings |
| `timer/` | Budget rules, watcher service, pause/block screen |
| `capture/` | Assistant capture session and share receiver |
| `data/` | App list, prefs, parking lot storage, permission helpers |
