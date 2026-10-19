# android-tv-launcher

Debloat scripts + a custom Apple TV-style home screen for Android TV / Google TV. No root; everything over ADB on the local network. README.md has the user-facing steps; this file is for coding agents.

## Layout
- `debloat/debloat.sh <tv-ip> [list]` hides packages with `pm uninstall -k --user 0` (reversible). `restore.sh <tv-ip> [pkg]` uses `cmd package install-existing`; with no package it restores the whole list and re-enables `com.google.android.tvlauncher`. Lists: one package per line, `#` comments. `adb shell -n` is required inside the `while read` loops, or adb swallows the rest of the list from stdin.
- `launcher/`: Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material`), `androidx.palette`. AGP 8.10 / Gradle 8.11.1 / Kotlin 2.0.21, JDK 17+, minSdk 28. No other dependencies (HTTP via HttpURLConnection, JSON via org.json).
  - `Apps.kt`: LEANBACK apps, banners, glow colour; favourites in SharedPreferences (`launcher`/`favourites`, seeded from `DEFAULT_FAVOURITES`); recents via UsageStatsManager (screensaver excluded).
  - `HomeScreen.kt`: pinned header (`TopBar` overlay; the Column starts with a `barHeight` spacer, and the vertical `minimalScroll` gets `topInset = barHeight` while the LazyRows get the plain spec re-provided). Once scrolled the bar fades in over a live blur: the page Box records into `contentLayer`, `captureBar()` draws it at 1/10 into `stripLayer`, `toImageBitmap()` + `blurredStrip()`; at most every 80ms while scrolling, every 500ms while scrolled, only while STARTED. Costs ~3-4 pts of janky frames on the SHIELD (baseline 3.8% → ~7%); `blurFailed = true` gives the frosted-backdrop bar instead. Header, trending hero, favourites in two row-major rows (150x84, five across at 960dp; move mode walks the whole list and refocuses via `movingTile` when a tile crosses rows) / Sports (240x112 score cards) / Inputs / Recent / All apps shelves (200x112); every tile shares `FocusTile`, long-press menu (Move, add/remove favourite, App info). Move mode: left/right, OK or Back finishes; the finishing key-up is swallowed or it launches the app. `OLED_CARE` gates idle/night dimming and pixel shift.
  - `Trending.kt`: TMDB `/trending/{feed}/week`, 8 titles, refreshed every 6h, retried every 15s until the first success. Config from `local.properties` → BuildConfig: `tmdb.key` (v3 key or v4 token), `tmdb.region` (default: device country), `tmdb.feed`. Button = first installed app in `PROVIDER_APPS` that streams it in the region, else `stremio:///detail/{movie|series}/{imdb}`. Backdrops kept as JPEG bytes; only previous/current/next decoded. Dark TMDB logos are rejected in favour of text.
  - `Sports.kt`: ESPN site API scoreboards (no key) for `sports.leagues` + `sports.teams` (`league:ABBR`) → BuildConfig. Window: live, starting within 18h, or started within 10h and final; college only ranked games plus yours; your idle teams get their next game from `/teams/{id}/schedule` (cached 6h; ids looked up from the league team list, since `/teams/{abbr}` fails for soccer/college). Max 12; card opens the first installed app for the game's network (`NETWORK_APPS`), else ESPN. Logos at 64px via ESPN's combiner, cached in memory. Polled from HomeScreen inside `repeatOnLifecycle(STARTED)`: 60s while live, 15 min otherwise.
 - `Glass.kt`: fake Liquid Glass (no RenderEffect before API 31). `Modifier.glass(shape)` draws a pre-blurred `GlassSource` lined up via positionInRoot. Home source = `TrendingTitle.frosted` (the backdrop blurred with Backdrop()'s masks baked in; keep `frostedBackdrop()` and `Backdrop()` in sync). Menu source = `captureBlurredWindow()` (PixelCopy at 160px + box blur). `blurredStrip()` blurs an already-small layer capture for the pinned bar (copies off the HARDWARE readback first).
  - `Inputs.kt`: HDMI inputs as `TvApp(isInput = true)`; CEC child inputs give the device name; hidden unless connected.
  - `Weather.kt`: last network location + Open-Meteo (no key). `Http.kt`: shared GET helpers. `Fonts.kt`: Inter variable font, `UiText` (opsz 14, default) / `UiDisplay` (opsz 32, for 20sp+).
  - `MainActivity.kt`: HOME activity; reloads on package changes; Home re-focuses the first favourite; Back swallowed. `launchFresh` kills the target's background process first (Live TV apps and Stremio ignore new inputs/links while running).

## Design rules (tvOS-like)
No row containers; rows bleed to the screen edge (`EdgePadding` as content padding). Focus = scale + app-colour glow + light rim + glare, never a thick border. Sentence-case type, weight not letter-spacing. Pills are glass; focused = solid white. The backdrop fades and parallaxes as the page scrolls.

## Build & deploy
```
cd launcher && cp local.properties.example local.properties   # sdk.dir + optional tmdb.*
./gradlew assembleRelease
S=<tv-ip>:5555
adb -s $S install -r app/build/outputs/apk/release/app-release.apk
adb -s $S shell pm grant me.prince.launcher android.permission.ACCESS_COARSE_LOCATION
adb -s $S shell appops set me.prince.launcher GET_USAGE_STATS allow
adb -s $S shell cmd package compile -m speed -f me.prince.launcher
adb -s $S shell cmd package set-home-activity me.prince.launcher/.MainActivity
```
Always ship release (R8); debug Compose is too slow on TV hardware.

## Rules when working on a user's TV
- Connecting: classic network ADB is `<ip>:5555`; TVs with "Wireless debugging" (Google TV, Android 11+) need `adb pair <ip>:<pairing-port>` with the on-screen code, then `adb connect <ip>:<port>`. The scripts accept `<ip>` or `<ip>:<port>`.
- Ask before removing any package. Never remove Live TV, `com.mediatek.*` / other chip services, the remote's Bluetooth service, `com.android.tv.settings`, Play Services, the keyboard or setup wizards.
- Disable (never uninstall) the stock launcher, and only after the custom launcher is installed and verified, otherwise Home shows a black screen. Fallback: `pm enable com.google.android.tvlauncher`. Warn the user that this can break hands-free "Ok Google" (README → Gotchas).
- Never commit or print the user's TMDB key; it belongs in `local.properties` only.
- Test by screenshotting: `adb shell screencap -p /sdcard/s.png` then `adb pull` (some TVs print vendor log lines into `exec-out` output, corrupting the PNG). Protected video and HDMI inputs capture as black.
- Before sending key events (`input keyevent`), check `dumpsys activity activities | grep mResumedActivity`: if a video app or TV input is in front, someone may be watching.

## Gotchas already handled in code
- Compose for TV pins the focused item 30% from the edge, scrolling the header off-screen; `minimalScroll()` (a custom BringIntoViewSpec) only scrolls when an item would leave the screen.
- On cold start the first tile isn't attached for a few frames; focus is requested with a short retry loop.
- The Wi-Fi name needs ACCESS_COARSE_LOCATION; VPN ad blockers make the VPN the active network, so the label checks all networks.
- The first TLS handshake after start sometimes fails (seen with AdGuard's HTTPS filtering); trending retries every 15s.
