# AxBrowser — Project Memory

This file exists so any AI agent (Claude Code, Claude Cowork, another Claude
session, or a different tool entirely) picking up this repo cold can get
oriented fast. Keep it updated as the project evolves — stale docs are worse
than no docs.

## What this is

AxBrowser is an Android web browser built from scratch in Kotlin/Jetpack
Compose, multi-module Gradle project, single-Activity architecture. Built
almost entirely through iterative AI-assisted sessions (Claude), with the
human owner (GitHub: `akborana3`) directing features and testing on a real
device while the AI writes/pushes code directly to GitHub.

Repo: `https://github.com/akborana3/AxBrowser`
Package: `com.akay.axbrowser` (release) / `com.akay.axbrowser.debug` (debug)

## Architecture

Clean Architecture, multi-module:
- `app/` — single Activity (`MainActivity`), `Application` class (`AxBrowserApp`), theme wiring, app-lock gate.
- `core/core-domain/` — pure Kotlin models + repository interfaces, no Android deps.
- `core/core-data/` — Room DB, DataStore preferences, repository impls, OkHttp client, crypto, proxy manager, crash logger, shared utils. Most cross-cutting infra lives here specifically so multiple `feature/*` modules can depend on it without circular deps.
- `core/core-ui/` — theme, shared Compose components, biometric auth helper.
- `feature/feature-browser/` — the WebView browser itself, tabs, ad-block, reader mode, AI agent, proxy UI glue, fingerprint spoofing injection. Depends on `feature-settings` and `feature-downloads`.
- `feature/feature-settings/` — Settings screens (general, proxy, passwords, filter lists, updates). Depends on `feature-downloads` and `core-data`, but **not** `feature-browser` (would be circular — `feature-browser` depends on `feature-settings`, not the other way).
- `feature/feature-downloads/` — yt-dlp + direct-http download engine, Download Manager screen, notifications.
- `feature/feature-bookmarks/`, `feature/feature-history/` — straightforward CRUD screens.
- `build-logic/convention/` — Gradle convention plugins. `AxAndroidApplication.kt` is where versionCode/versionName, signing configs, and build types are defined.

### Dependency direction rule (important, bit us with circular deps before)

`feature-browser` → `feature-settings` → `feature-downloads` → `core-*`

Never make `feature-settings` or `feature-downloads` depend on `feature-browser`.
When something needs to be shared between browser and settings (e.g.
`ProxyManager`, `ApkInstaller`), it goes in `core-data`, not either feature
module.

## DI patterns used throughout

- Concrete `@Singleton @Inject constructor` classes need no Hilt module (just inject directly).
- Interfaces (`XxxRepository`) get bound via `@Binds` inside `BrowserModule.kt` (in `feature-browser/di/`) — even repositories that live in `core-data`/`core-domain` are bound there, since that's where the pattern was established. Hilt aggregates `@Module`s across the whole graph at the final `:app` compile, so it doesn't matter which Gradle module physically declares the `@Binds`, only that it ends up on `:app`'s classpath (which it does, since `app` depends on `feature-browser`).
- ViewModels are `@HiltViewModel`, obtained via `hiltViewModel()` in Compose. **Gotcha**: `hiltViewModel()` calls are scoped to the current `NavBackStackEntry` by default — if two different screens need to share the *same* ViewModel instance (e.g. `DownloadViewModel` needs to be visible from both the Downloads screen and the Settings "App Updates" section), obtain it once at a shared parent scope (`BrowserNavHost`'s own composable) and pass it down explicitly as a parameter. Forgetting this caused a real bug: update downloads started from Settings were invisible in the actual Downloads screen because Settings had its own default-scoped instance.

## Data layer conventions

- Room DB: `AxDatabase` in `core-data`, currently at **version 5**. Uses `fallbackToDestructiveMigration()` — schema changes wipe local data on that specific migration, which is fine for a personal project but bump the version number every time you add/change an entity or column, or Room will throw an identity-hash mismatch (not just silently ignore it).
- Sensitive fields (passwords, proxy credentials, OpenRouter API key) are encrypted at rest via `CryptoManager` (`core-data/security/`), which uses AES/GCM backed by the Android Keystore. Always wrap `cryptoManager.decrypt()` calls in `runCatching` — a key can become invalid after certain system events, and this must never crash reads.
- Simple preferences live in DataStore via `AxPreferences` (`core-data/datastore/`) — one big class, follow the existing `Keys` object + `Flow` + suspend setter pattern for anything new.

## Features implemented so far (roughly chronological)

1. Reader mode, edge-swipe back/forward navigation (as a thin edge-only overlay, **not** a full-surface gesture — a full-surface `pointerInput` on the WebView causes janky scrolling by fighting the WebView's native touch handling; this was a real bug that had to be fixed once already).
2. Address bar search suggestions/autocomplete.
3. Ad-block filter-list subscriptions (multiple lists, per-site allowlist toggle) via `AdBlockEngine`.
4. Password manager with Keystore encryption, save-prompt on form submit, autofill.
5. Biometric/PIN app lock (`MainActivity` gates content behind `AppLockScreen` if enabled).
6. Wi-Fi-only downloads, battery-saver image blocking.
7. Bookmark import/export (Netscape Bookmark HTML format).
8. Tab groups — real Chrome-style stacked-window UI (not just a label), long-press-and-drag grouping. Lives in `TabSwitcherScreen.kt`.
9. **AI Agent** (`feature-browser/agent/`) — chat-based browser automation using OpenRouter's free-tier models. Uses a **ReAct-style JSON-action loop** (`AgentEngine`), not native OpenAI function-calling, because free-tier model support for function calling is inconsistent. Tools: navigate, search, get_page_text, get_links, click_link, scrape (CSS selector), run_js (raw JS execution), get_network_requests / get_detected_media (reads from the dev-console's `NetworkInterceptor`), go_back/forward, list_tabs, download. The chat UI (`AgentSheet.kt`) has a collapsible "Thinking" panel per turn and persists across minimize (state lives in `AgentChatController`, not Compose `remember`, so backgrounding the sheet doesn't lose the conversation).
10. Proxy support — HTTP/HTTPS/SOCKS4/SOCKS5, multi-proxy rotation (manual/per-navigation/timed), bypass exceptions. Uses Android's real `androidx.webkit.ProxyController` API. **Important gotcha**: Chromium's proxy rule grammar (`scheme://host:port`) does **not** support embedded `user:pass@` credentials — a rule with credentials gets rejected outright with "Invalid Proxy URL". Authenticated proxies are handled by sending the bare rule and answering the resulting HTTP 407 challenge via `WebViewClient.onReceivedHttpAuthRequest`. `ProxyManager` lives in `core-data` (not `feature-browser`) specifically to avoid the circular-dependency problem when `feature-settings`' Proxy screen also needs it.
11. Device fingerprint protection — canvas/WebGL/hardware spoofing via `WebViewCompat.addDocumentStartJavaScript` (must be document-start, not post-load, or fingerprinting scripts run before the spoof does). Deterministic per-seed so it's stable within a "device identity"; regenerable via Settings.
12. In-app update checker/downloader/installer — see "Update mechanism" section below, it's got real gotchas.
13. Crash logger (`core-data/crash/CrashLogger.kt`) — installs a global uncaught-exception handler, writes the stack trace to a file AND copies it to the clipboard immediately, viewable later in Settings → Diagnostics. This exists because there's no remote crash reporting; when something crashes on-device, the human pastes the clipboard content back into the AI session to debug it. **This has already been the deciding factor in finding at least one real bug** (a Kotlin class-initialization-order NPE — see "Known bug classes" below).

## Update mechanism (has real gotchas, read before touching)

Two parallel channels:
- **`release.yml`** — triggers on `v*` git tags, produces the **release**-signed, formally versioned build. Rarely used in practice so far (the human hasn't been tagging releases).
- **`ci.yml`** — triggers on every push to `main`. Builds the **debug** variant (package suffix `.debug`), and publishes/updates a rolling GitHub Release tagged `nightly` (force-updated every run, marked `prerelease: true`) with the APK attached as a public downloadable asset. Version is `1.0.<github.run_number>` — this is what the human has actually been testing with, since that's the build produced by every regular push.

**Both build types are signed with the same committed keystore** (`keystore/ci-release.keystore`, alias `axbrowser`, password `axbrowser123` — documented in `keystore/README.md`, intentionally not a secret, just a consistency mechanism). This is required for in-place updates to work at all: Android refuses to install an update over an app with a different signing certificate, and GitHub Actions runners are ephemeral — without a committed keystore, every CI run would generate a fresh random one and updates would silently break (this happened once already and had to be fixed).

The in-app update checker (`UpdateViewModel` in `feature-settings`) specifically queries `GET /repos/{owner}/{repo}/releases/tags/nightly` — **not** `/releases/latest`, because that endpoint silently excludes prereleases and would never find the nightly channel.

Version comparison: reads the installed app's own `PackageInfo.versionName` and compares against the nightly release's version (parsed from the release *name* field, e.g. "Nightly build v1.0.95" → "1.0.95"; the tag itself is always just the literal string "nightly").

Download-then-install flow: downloading goes through the shared `DownloadViewModel` (see the scoping gotcha above — must be the same instance as the real Downloads screen uses). Because the user can background the app or navigate away mid-download, "ready to install" state is **persisted to `AxPreferences`** (`pendingUpdateApkPath`), not just held in Compose `remember` state — survives navigation, backgrounding, and process death. There's also a proactive check: after every "check for updates", if the expected APK filename already exists on disk in the downloads directory, it's treated as ready-to-install immediately without re-downloading.

Installing: `ApkInstaller` (`core-data/util/`) — shared between the Settings "Install update" button and the Downloads screen's own "Install" action for any `.apk` file. Checks `canRequestPackageInstalls()` first and redirects to the system permission screen if needed, otherwise fires `ACTION_VIEW` with a `FileProvider` URI and the APK mime type.

Data preservation across updates: as long as applicationId and signing certificate both stay consistent (they now do, see above), Android's package installer performs a true in-place update and **automatically preserves all app data** (Room DB, DataStore, internal files) — this is default OS behavior, nothing in this codebase needs to explicitly handle it. If data loss on update is ever reported again, suspect a signing/applicationId mismatch first, not application code.

## Known bug classes to watch for (things that have actually bitten this project)

1. **Kotlin class-member initialization order.** Properties and `init {}` blocks run in *textual* order within a class body. If an `init {}` block launches a coroutine that writes to a property declared *later* in the same file, and that coroutine's first emission can resolve synchronously/fast enough, you get an intermittent NPE on the property reference itself (not a null *value* — the field itself is uninitialized). This caused a real "app force-closes sometimes but not always" production bug (fixed by moving `BrowserViewModel`'s fingerprint-related properties before its `init {}` block). **Rule of thumb: any property written to by code called from `init {}` must be declared before that `init {}` block.**
2. **WebView + Compose pointerInput gesture conflicts.** A `pointerInput`/gesture-detector modifier applied to the *entire* WebView's Compose wrapper fights the WebView's native scroll/touch handling and causes visible jank. Any custom gesture (edge-swipe, etc.) must be scoped to a small, non-overlapping region (e.g. a thin edge strip), never the full WebView surface.
3. **`shouldInterceptRequest` runs on a background thread.** Never call any `WebView` method (including `view.getUrl()`) from inside it — this throws a fatal "WebView methods must be called on the same thread" exception on modern WebView builds. Cache anything needed (like the current page origin) from `onPageStarted`/`onPageFinished`, which *do* run on the UI thread, into a `@Volatile` field instead.
4. **`LazyColumn`'s `item {}` DSL vs plain `Column`.** `SettingsScreen.kt` uses a plain scrollable `Column`, not `LazyColumn` — a `item { }` call there is a compile error (`Unresolved reference: item`). Double-check which container type you're actually inside before reaching for lazy-list DSL functions.
5. **Chromium proxy rules don't support embedded credentials.** See "Proxy support" above.
6. **Hilt cross-module `@Binds`**: fine to declare in a different Gradle module than where the interface/impl live, as long as it ends up on `:app`'s final classpath. But circular *project* dependencies (`implementation(project(":..."))`) are a hard Gradle error, not just a lint warning — always check the dependency direction rule above before adding a new cross-module import.
7. **`combine()` with heterogeneous flow types**: kotlinx.coroutines only has typed `combine()` overloads up to 5 flows with distinct generic types each; beyond that (or when mixing this pattern loosely) you fall back to the vararg `combine(vararg flows: Flow<T>, transform: (Array<T>) -> R)` form, which requires a *single* common `T` — usually forces `Any` and manual casts. Prefer the typed 2-5-flow overloads with explicit lambda params when possible; it's less error-prone.

## Debugging workflow this project actually uses

This sandbox/session has **no Android emulator and no device** — nothing here can run the app. Two techniques compensate:

1. **CI compile errors**: GitHub's job-log API redirects to Azure Blob Storage, which isn't reachable from a typical sandboxed environment, and generic URL-fetch tools usually refuse to follow that redirect either. Workaround: temporarily add a step to the workflow that captures `./gradlew` output to a file and **commits it to a throwaway git branch** (e.g. `ci-debug-logs`), which can then be read via a normal `git fetch`/`git show` — no special API access needed. Always revert this temporary logging step once the real error is found and fixed; don't leave it in `ci.yml` permanently (it adds noise and an extra commit per run).
2. **Runtime crashes**: can't be reproduced or debugged locally at all. The `CrashLogger` (see above) exists specifically to turn "the app crashed for some unknowable reason" into "here's the exact stack trace, pasted from the clipboard" — this is the only real way to debug a runtime issue in this project's current setup. When a crash report comes in, read the full stack trace carefully; it points at the exact file/line, don't guess.

Before pushing any nontrivial change, it's worth running a manual brace/paren-balance scan (a small Python script that walks the file respecting string/comment context) across every changed `.kt` file — this has caught several otherwise-invisible mistakes (mismatched fully-qualified names mid-expression, broken helper functions, stray brackets from careless edits) before wasting a CI round-trip.

## Things NOT yet done / possible next steps

- Formal `release.yml` channel is basically unused in practice; if the project ever needs a "stable" vs "nightly" distinction for real users, that's already scaffolded but untested end-to-end.
- No automated tests worth mentioning beyond whatever `./gradlew testDebugUnitTest` picks up by default — this is a feature-velocity project, not a test-first one.
- Fingerprint spoofing covers canvas/WebGL/hardware only — no WebRTC IP leak protection, no font-enumeration defense, no audio-context fingerprint defense.
- AI agent has no persistent multi-session memory (each "New Chat" starts fresh); within a session it does retain conversation history across turns via `AgentEngine`'s internal message list.
