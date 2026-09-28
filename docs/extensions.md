# Chrome extension compatibility bridge

This adds a **partial Manifest V3 runtime to the existing Android WebView browser**. It is not Chromium's extension engine, cannot run every Store extension, and must not be advertised as universal Chrome extension support.

## Existing architecture and integration

The project uses Compose, Hilt and repository interfaces backed by Room. `MainActivity` starts `BrowserNavHost`. `BrowserScreen` owns a visible WebView, `AxWebViewClient` handles navigation/ad blocking/network inspection, and `AxWebChromeClient` handles browser chrome. `BrowserViewModel` observes `TabRepository`; the current browser renders one tab at a time. Downloads are actually queued by the shared `DownloadViewModel`, not by `DownloadRepository`, so the bridge delegates to that ViewModel. Bookmarks and history reuse `BookmarkRepository` and `HistoryRepository`. Cookies reuse Android `CookieManager`.

Proxy configuration remains in `ProxyManager`, settings in `AxPreferences`, and all existing user scripts, ad blocking, agent/scraping, file manager, password, media, and developer/security modules remain. No database schema, browser engine, navigation destination, or existing download implementation is replaced.

### Modified existing files

| File | Change |
| --- | --- |
| `.gitignore` | Ignore generated module build directories and Kotlin caches. |
| `.github/workflows/ci.yml`, `.github/workflows/release.yml` | Align Gradle with the wrapper; run JavaScript bridge regression tests in CI. |
| `gradle/libs.versions.toml`, `build-logic/convention/build.gradle.kts` | WebKit 1.16.0 provides public isolated-world APIs. AGP 8.7.3 and Hilt 2.53.1 handle its Kotlin 2.1 metadata. Device support is checked at runtime. |
| `feature/feature-browser/build.gradle.kts` | DocumentFile folder import, JVM JSON test dependency and JUnit Platform test discovery. |
| `feature/feature-browser/.../ui/BrowserNavHost.kt` | Own shared extension/browser ViewModels and a native Extensions overlay. Existing userscript manager stays accessible. |
| `feature/feature-browser/.../ui/BrowserScreen.kt` | Extension menu, native Store install banner, navigation events, registration before loading, and tab-specific WebView cleanup. |
| `feature/feature-browser/.../webview/AxWebViewClient.kt` | Optional visited-URL hook so SPA Store navigation updates the native installation banner. |
| `feature/feature-browser/.../viewmodel/BrowserViewModel.kt` | Reflect repository tab activation/URL updates initiated by extension APIs. |
| `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | Restore the official Gradle 8.9 wrapper and pin the distribution SHA-256. Both downloads were checksum verified. |

The browser library also declares the existing app's network-state and notification permissions in `src/main/AndroidManifest.xml` so lint can validate the library in isolation. Notification posting checks the Android 13 runtime grant before dispatch.

All implementation classes are under `feature/feature-browser/src/main/kotlin/com/akay/feature/browser/extensions/`:

| File | Responsibility |
| --- | --- |
| `ExtensionManifest.kt` | MV3 models, content scripts, host match patterns, safe resource paths. |
| `CrxVerifier.kt` | Bounded CRX3 header/protobuf parsing and RSA/ECDSA archive signature and ID validation. |
| `ExtensionInstaller.kt` | SAF folders/packages, staged extraction with limits, Store listing recognition/retrieval. |
| `ExtensionManager.kt` | Atomic registry, private package snapshots, enable/disable/uninstall. |
| `ExtensionRuntime.kt` | Context lifetime, background hosts, events, message responses, scripting. |
| `ExtensionBridge.kt` | Versioned RPC dispatcher, context and permission checks, error responses. |
| `ExtensionWebViewBridge.kt` | Private extension origins, popup/options/background resource host and CSP. |
| `ContentScriptManager.kt` | Isolated top-frame worlds and document start/end/idle injection. |
| `ExtensionViewModel.kt` | Installation/optional permission consent, existing download binding, lifecycle and UI state. |
| `api/BrowserApis.kt` | Tabs/windows/downloads/history/bookmarks adapters to existing browser components. |
| `api/PermissionsApi.kt` | Required/optional API and host grants. |
| `api/StorageApi.kt` | Per-extension local and session storage, quotas, atomic writes and events. |
| `api/CookiesApi.kt` | Explicit-URL CookieManager compatibility. |
| `api/ExtensionUiApi.kt` | Action state, page menu items, manual commands and basic Android notifications. |
| `api/CapabilityRegistry.kt` | Method allowlist and user-visible limitations. |
| `ui/ExtensionsPanel.kt` | Material 3 management, consent, compatibility and extension-page UI. |

The JS shim is `src/main/assets/extensions/chrome_bridge.js`. The runnable sample is `examples/extensions/hello-ax/`. Tests are `src/test/kotlin/com/akay/feature/browser/extensions/ExtensionSecurityTest.kt` and `scripts/test-extension-shim.cjs`.

## Runtime and protocol

```text
extension code → chrome.* shim → origin/world-bound WebMessageListener
              → ExtensionBridge → permission-checked API adapter
              → existing browser repositories / DownloadViewModel / CookieManager
```

Requests are `{v:1,id:"7",method:"storage.local.get",args:["theme"]}`. Replies are `{id:"7",result:...}` or `{id:"7",error:{message:"..."}}`. Native events use `{event:"tabs.onActivated",args:[{tabId:1,windowId:1}]}`. Messaging responses are tied to a native random token **and** their receiving endpoint. JavaScript never supplies an authoritative extension ID, context type, tab ID for its sender, or native class/method name. Endpoint identity comes from the registered WebView/listener/world. Navigation retires endpoints and pending messages. Replies have bounded timeouts; messages are capped at 1 MiB.

The shim supports Promises and Chrome callbacks. Callback failures set `runtime.lastError` during the callback only. Unknown methods reject with an explicit unsupported error. Events support listener add/remove/has checks. `runtime.getManifest`, `runtime.getURL` and i18n helpers are synchronous.

Document replacement is detected by the new document's native reply-proxy handshake. It retires the previous endpoint and its pending responses. Do not invalidate the new endpoint from `onPageStarted`: WebView 103 was observed delivering that callback after document-start JavaScript and the popup's first message. Explicit view disposal, script re-registration, disable and uninstall still invalidate immediately; every request also checks the live origin, package and permissions.

### Manifest and lifecycle

Only MV3 is accepted. The parser handles `background.service_worker` (including module entrypoints), `content_scripts`, `action`, `permissions`, `host_permissions`, `optional_permissions`, `optional_host_permissions`, `options_page`/`options_ui`, icons and commands. Original manifest fields remain available through `getManifest`; unsupported fields are not evidence of support. `web_accessible_resources` are **not** exposed to normal pages in this implementation.

Install/import → bounded private staging → parse and validate referenced files → native permission review → copy into an immutable private package snapshot → atomically register → start background → register content scripts. Updates require a fresh permission review. Disabled/uninstalled extensions lose bridge access and background hosts; uninstall also deletes storage and optional grants. Existing DOM changes require a page reload to disappear. New content scripts apply on the next document load.

Background logic runs in a private offscreen WebView document. Classic `importScripts` is adapted with synchronous same-package imports; module entrypoints use normal module scripts. This is **service-worker-style emulation**, not a browser service worker: no OS wakeups, suspend/resume persistence, alarms, push, or execution after the app process dies. Background code can observe a DOM, unlike a real Chrome worker. Lifecycle events wait for background page load. Classic imports resolve relative to the background entrypoint. Network requests to remote hosts are blocked in extension pages/backgrounds in this version.

### Content scripts

Each extension gets its own named `JavaScriptExecutionWorld` per visible browser WebView. HTTP(S) match patterns and exclusions are checked against full URLs; wildcard hosts preserve domain boundaries. Scripts register before navigation. `document_start` and `document_end` use WebKit injection events; `document_idle` schedules after document end. CSS is injected into the shared DOM, JavaScript remains in its isolated world.

The implementation is top-frame only. `all_frames`, MAIN world, glob filters and about:blank/origin-fallback matching are explicitly rejected rather than approximated insecurely. Chrome Web Store pages and private extension hosts are protected from injection. Extensions never run in incognito tabs. On providers without `JS_INJECTION_IN_FRAME_AND_WORLD`, content scripts and scripting are disabled; **there is no privileged page-world fallback**. A device with new AndroidX app libraries but an old System WebView still lacks this capability.

## API compatibility

Every row below is partial; the capability screen lists individual callable methods.

| API | Implemented | Limits |
| --- | --- | --- |
| runtime | Manifest/URL/platform helpers, one-shot internal messaging, options, install/startup/message events | No ports, external messaging, native messaging or updates API. Messages target one other live context. |
| storage | local/session get/set/remove/clear/bytes, change events | 5 MiB quota per area; no sync service; session is extension-page-only. |
| tabs | Query/get/create/update/remove/reload, messaging, creation/update/removal/activation events | UUIDs mapped to session numeric IDs; one rendered tab; no incognito; no pinned/muted support; only visible tab reload. Sensitive fields require tabs or host permission. |
| scripting | Files/functions/args and CSS in a live isolated top frame | Requires explicit host grant and active rendered context; no MAIN world, subframes, registered scripts or removeCSS. |
| cookies | Explicit-URL get/getAll/set/remove | WebView exposes cookie-header values, not full metadata. No domain/global enumeration, partition keys or change events; write host-only cookies. |
| webNavigation | Top-frame before/complete/error events | Host permission required; no frame trees, SPA history events or full desktop ordering guarantees. |
| windows | Read methods for the browser's single window | No creation/removal or desktop window sizing. |
| downloads | Existing enqueue/search-by-ID/pause/resume/cancel and basic created/changed events | No second engine, custom headers/body, save-as or arbitrary path. Existing download IDs mapped to session integers. |
| notifications | Basic Android create/clear | Android notification permission must already be enabled; no click/button callbacks. |
| contextMenus | Flat page actions create/update/remove/removeAll and click dispatch | Shown as native actions inside Extensions; not a full desktop right-click menu; no selection/link/submenus. |
| permissions | Get/contains/request/remove optional grants with native approval | Only declared implemented permissions; removal of required grants requires disabling extension; host containment uses exact requested patterns. |
| i18n | UI language, localized message lookup and placeholders | No language detection; extension display name localization is not resolved by the manifest parser. |
| action | Badge/title/popup state and click dispatch | Presented in Extensions; no per-tab state, icon replacement or automatic toolbar pinning. Badge color is stored but not rendered. |
| commands | Enumerate and manually dispatch declared commands | Native buttons, no global keyboard shortcut registration. |
| history | Search/add URL/delete URL/delete all through existing repository | No visit-detail/events API. |
| bookmarks | Read/search/tree/create/update/remove through existing repository | Existing folders appear in tree; folder creation/move/reorder and events are unsupported. |

`activeTab` temporary grants, webRequest, declarativeNetRequest, devtools extension panels, nativeMessaging, storage.sync, offscreen, and other desktop APIs remain unsupported. Ad-blocking extensions depending on declarativeNetRequest will not function; AxBrowser's existing ad blocker remains in use.

## Store installation

Opening an HTTPS Chrome Web Store detail URL shows a **native Add to Browser** banner. The ID is parsed from the exact official hostname/path; page JavaScript cannot start an installation. The user taps the native banner, the installer attempts Google's public CRX update endpoint, follows a bounded set of HTTPS redirects only to Google/Googleusercontent hosts, verifies CRX3 signatures plus the selected extension ID, and shows the native permission review before execution.

This endpoint is a **best-effort retrieval mechanism, not a guaranteed/supported third-party Store-install contract**. Google can refuse downloads or change its service. The app does not scrape sign-in cookies, bypass account/payment restrictions, or use third-party package mirrors. Failures offer local CRX/ZIP/folder import. Verification proves package integrity and the signing-key-derived extension identity; it does **not** pin Google's publisher proof or certify the package as safe. Unsigned local packages are labeled explicitly.

## Security boundaries

- No new `addJavascriptInterface` is added to browsing or extension WebViews. Existing browser bridges are left untouched; this feature is not an audit of those pre-existing bridges.
- Extension-page bridges are limited to `https://<id>.ax-extension.invalid`, main frames, an enabled immutable package, and its dedicated WebView. All requests in these WebViews are intercepted; missing or external resources receive an error without DNS/network fallback. Arbitrary navigations, file/content access, popups and WebView device permissions are denied.
- Content bridges exist only in isolated worlds. Website JS cannot access their transport or chrome API objects. Shared DOM changes do not grant native privileges.
- Native dispatch enforces API permissions, context restrictions, per-extension storage, host permissions for scripting/cookies and private-tab exclusion. `chrome.tabs` metadata is redacted without appropriate access.
- Installation limits: 32 MiB archive, 64 MiB extracted, 8 MiB per file, 4096 entries, folder depth 20. Canonical paths reject traversal, absolute paths, Windows drive names and duplicate case-folded ZIP entries. ZIP symlink contents are treated as ordinary file data, not created as links.
- Extension pages use a restrictive CSP and nosniff. Background import emulation alone permits eval for local scripts; popup/options pages do not. No remote scripts/frames/objects or public extension-resource exposure.

## Validation and device checklist

```sh
node --test scripts/test-extension-shim.cjs
./gradlew :feature:feature-browser:testDebugUnitTest :app:assembleDebug
```

JVM security tests cover CRX signature tampering and identity mismatches, malformed headers, host matching, restricted schemes, Store URL spoofing, unsafe resource paths, manifest restrictions and input size bounds. Node tests cover callback errors, Promises, events, messaging, function serialization, i18n and unsupported APIs.

Device acceptance checks (required before calling this production-ready):

1. Import `examples/extensions/hello-ax/` as a folder or ZIP with manifest at root; cancel once and verify no extension executes, then approve.
2. Open the popup, save a new options greeting, visit/reload `https://example.com/`, and verify the content badge and background response.
3. In website devtools verify `window.AxExtension` is absent and the extension's isolated chrome objects are inaccessible. Try a child frame and mismatched domain.
4. Disable/uninstall while requests are in flight; verify old contexts lose privileges, and storage disappears only on uninstall.
5. Test Store success, denied/unavailable packages, wrong-ID/tampered CRX, permission cancellation and offline errors.
6. Test tabs created/updated/closed by an extension and original browser controls; bookmark/history consistency and existing download pause/resume/cancel.
7. Check old WebView providers show the capability warning, and incognito pages receive no extension scripts or events.
8. Recheck proxy, ad blocker, userscripts, developer console, agent, media downloads and file manager. No physical device/emulator result should be inferred from JVM tests alone.

## Primary references

- [AndroidX isolated JavaScript worlds](https://developer.android.com/reference/androidx/webkit/JavaScriptExecutionWorld)
- [WebViewCompat injection and message listeners](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- [Chrome extension distribution mechanisms](https://developer.chrome.com/docs/extensions/how-to/distribute)
- [Chromium CRX3 format](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/crx_file/crx3.proto)
