# AxBrowser

A fast, secure, privacy-first Android browser with built-in smart media detection and local download engine.

## Features

- **Lightning Fast** — Hardware-accelerated WebView, smart cache, low RAM usage
- **Privacy First** — Ad/tracker blocker, incognito mode, HTTPS upgrade
- **Smart Media Detection** — Auto-detect downloadable media with floating download button
- **Built-in Download Engine** — yt-dlp for video sites + OkHttp for direct files
- **Paste Link Download** — Like 1DM/ADM, paste any URL to download
- **Dev Console** — Network Inspector + Eruda JavaScript console
- **Material 3 Design** — Glassmorphism UI, premium feel
- **Tab Management** — Multiple tabs, incognito mode, tab switcher
- **Bookmarks & History** — Save and organize your favorite sites
- **Built-in Video Player** — Play downloaded videos with ExoPlayer
- **File Manager** — Browse and manage downloaded files

## Download

[**Download Latest APK**](https://github.com/akborana3/AxBrowser/releases/latest)

## Screenshots

<!-- Screenshots will be added after first release -->

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** Clean Architecture + MVVM
- **DI:** Hilt
- **Database:** Room
- **Networking:** OkHttp
- **Async:** Kotlin Coroutines + Flow
- **Video:** Media3/ExoPlayer
- **Background:** WorkManager
- **Download Engine:** yt-dlp + OkHttp

## Project Structure

```
AxBrowser/
├── app/                    # App entry point
├── core/
│   ├── core-ui/           # Theme, components, utilities
│   ├── core-domain/       # Models, repositories, use cases
│   ├── core-data/         # Database, network, datastore
│   └── core-testing/      # Test helpers and fakes
├── feature/
│   ├── feature-browser/   # WebView, tabs, navigation, dev console
│   ├── feature-downloads/ # Download engine (yt-dlp + OkHttp), manager UI
│   ├── feature-bookmarks/ # Bookmark manager
│   ├── feature-history/   # History store
│   ├── feature-settings/  # App settings
│   ├── feature-filemanager/ # File browser
│   └── feature-videoplayer/ # Video player
└── build-logic/           # Convention plugins
```

## Getting Started

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run on device or emulator (minSdk 26)

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

## CI/CD

This project uses GitHub Actions for continuous integration:

- **CI Workflow** — Builds on every push, runs tests and lint, publishes the rolling **Nightly build** pre-release, and sends the APK to Telegram
- **Release Workflow** — Creates GitHub Release with APK when a version tag is pushed, and sends the release APK + Play Store bundle to Telegram
- **Telegram check** — Manual, ~1 minute: verifies the Telegram credentials without an Android build, and can mint a reusable session

### Telegram APK delivery

Every successful `main` build sends the **whole APK** to the configured Telegram chat — not a download link.

| | |
|---|---|
| Uploader | `scripts/telegram_upload.py` (Telethon / MTProto) |
| Why MTProto | the HTTP Bot API hard-caps `sendDocument` at 50 MB, so a ~120 MB APK is always rejected (`413`) |
| Session | the MTProto login is cached between builds, so the bot is not signed in again on every build |
| Caption | version, versionCode, build number, filename, exact bytes + MB, SHA-256, branch @ sha, UTC time, CI duration, unit-test and lint outcomes, commit subject, commit/run/release URLs |
| Blocking? | no — every step is `continue-on-error`, so Telegram can never redden a build |

Credentials live in **Settings → Secrets and variables → Actions**:

- `TELEGRAM_BOT_TOKEN` — token from @BotFather (**required**)
- `TELEGRAM_CHAT_ID` — numeric id (`-100…`) or `@username` (**required**)
- `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` — optional overrides; the workflow defaults are the app the bot was created under, so this usually stays unset (an api_hash is always 32 hex characters — a shorter value means it was cut short, and Telegram refuses the login)
- `TELEGRAM_SESSION_STRING` — optional; takes priority over the cached session file

If MTProto cannot log in the upload degrades instead of failing:

1. **MTProto document** — the whole file, up to 2 GB
2. **Bot API `sendDocument`** — only possible under 50 MB
3. **Bot API parts** — a larger APK split into sub-50 MB documents, plus the one-line command that joins them back together
4. **Info message** — the full build info with the GitHub release link

Each fallback states the reason in the chat itself, so a failure is diagnosable from a phone instead of from the Actions log. A saved session that Telegram has revoked is re-created automatically on the next build. Telegram counts captions in UTF-16 code units (one emoji is 2), so the caption is trimmed to 1024 and the untouched text follows as a message rather than being lost.

To test the credentials without waiting for a build: **Actions → Telegram check → Run workflow** (`mode=session` instead writes a session string artifact you can store as `TELEGRAM_SESSION_STRING`).

> Uploading ~120 MB over MTProto from a GitHub runner takes roughly 20 minutes: Telethon sends the file sequentially and Telegram throttles a single connection. That is the price of delivering one complete file rather than split parts.

### Creating a Release

```bash
git tag v1.0.0
git push --tags
```

The release workflow will automatically:
1. Build the release APK
2. Sign the APK
3. Create a GitHub Release
4. Attach the APK as a downloadable artifact
5. Send the release APK and the Play Store `.aab` to Telegram over MTProto

## License

MIT License
