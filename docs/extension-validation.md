# Extension validation

## Build and automated checks

- `:app:assembleDebug`: passed with Gradle 8.9, AGP 8.7.3, Hilt 2.53.1 and Android Studio's JDK 21.
- `:feature:feature-browser:testDebugUnitTest`: 10 tests, zero failures/errors, rerun after the device lifecycle fix.
- `node --test scripts/test-extension-shim.cjs`: 11 passing tests.
- GitHub Actions built the initial implementation and passed its unit-test step. The existing workflow makes lint non-blocking, so its green job does not establish a clean lint result.
- Lint remains blocked by an upstream Compose checker crash: `ComposableCoroutineCreationDetector` / `null cannot be cast to non-null type org.jetbrains.uast.UParameter` in the existing `DownloadManagerScreen.kt`. It also fails with K2 UAST explicitly enabled. No lint rule was disabled to conceal this failure.

## BlueStacks checks

Environment: BlueStacks 5 Pie64, Android 9, Chrome WebView 103.0.5060.129, 1600 x 900. Installed the separate `com.akay.axbrowser.debug` package.

| Check | Observed result |
| --- | --- |
| Install and launch | Passed; existing home/navigation UI renders. |
| Native Extensions manager | Passed; compatibility warning shown for unsupported isolated worlds. |
| ZIP import and permission review | Sample package parsed; native review lists APIs and host access; cancel and subsequent approved import exercised. |
| Background startup | Sample context-menu action appears; background storage API returns saved data. |
| Popup messaging | Popup displays `Hello from AxBrowser` from the background. |
| Tabs query | Popup's current-tab button returns the actual browser URL. |
| Options | Popup opens options; Save writes `Tested on BlueStacks` to native extension storage. |
| Disable/re-enable | Disable removes the background WebView (zero background targets); re-enable restores it and preserves the greeting. |
| Popup reopen | Displays the greeting saved through options. |
| Tab create/get/remove | Created an inactive example.com tab, read it back, removed it, and verified it was absent. |
| Unsupported API | `tabs.captureVisibleTab` rejects with an explicit unsupported API error. |
| Website bridge exposure | A normal website has neither `window.AxExtension` nor the extension `chrome.runtime`. |
| Crash log | No AndroidRuntime crash reported during these checks. |

The device test found and fixed a real ordering issue: WebView 103 can deliver `onPageStarted` after a popup's document-start bridge connection. The new document's native reply-proxy handshake now retires the previous endpoint; a late navigation callback no longer disconnects the new popup. Temporary diagnostic logging was removed from the delivered build.

## Practical limits and remaining checks

- This provider cannot run the isolated-world content-script API. The app correctly disables website scripts; successful isolated injection and its modern-provider security checks remain unverified on a device.
- Google's public update endpoint returned a real Google Translate CRX3 package; its signature and requested extension ID passed verification. This establishes retrieval/integrity, not runtime compatibility.
- Opening the Store listing through ADB for an end-to-end device check was rejected by automatic approval review with `blocked by policy` and no more specific reason. That check remains unverified.
- Folder/CRX import UI, uninstall/storage deletion, optional grants, cookies, downloads, history, bookmarks, notifications and the full original-browser regression checklist need wider device testing. The implemented API matrix and limitations are in `extensions.md`.
- Compatibility is partial. There is no full Chromium extension engine, true service-worker wakeup, remote networking in extension contexts, all-frame injection, activeTab grant, or network-interception API support.

The pull request remains draft for review and broader device validation.
