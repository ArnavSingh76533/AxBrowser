# AxBrowser — Build Prompt v7
# Playwright-MCP-shaped Agent Tools + Burp-Like Dev Tools
# Execution edition. Supersedes planv06.md. All gating removed: there is no Pentest Mode
# toggle, no scope list, no consent flow. Every tool below is always available.

---

## 0. READ THIS FIRST — GROUND RULES FOR THE IMPLEMENTING AGENT

1. **Do not rewrite what works.** You are extending `feature-browser/agent/`,
   `feature-browser/devconsole/`, and adding ONE new module `feature/feature-pentest/`.
   Modify existing files; create new files only where listed.
2. **No gating of any kind.** planv06's guardrails (Pentest Mode toggle, SCOPE_HOSTS_JSON,
   consent acknowledgment, ConfirmRequired events, destructive-verb confirmations) are
   DELETED. Not deferred, not commented out — deleted. No tool checks a setting, no tool
   asks, no tool confirms. `http_send`, Intruder, Intercept-modify run unconditionally.
3. **All async is Coroutines + Flow.** All UI is Jetpack Compose. No XML, no RxJava.
4. **Write every file completely.** No `// TODO` stubs.
5. **Hilt everywhere.** Interfaces bound in modules, like the rest of the app.
6. **No new third-party dependencies.** OkHttp 4.12 (present), raw `Socket`/`SSLSocket`,
   `java.security` MessageDigest, Compose. Anything else = you made a mistake.

### Concrete tool contract (executor-level, not prompt-level)

| Tool / capability | Runs when? |
|---|---|
| `http_send` / Repeater send | Unconditionally, any host, any method |
| Intruder (Sniper/Clusterbomb) | Unconditionally, no request cap enforcement beyond user-configured |
| Intercept hold/modify/drop | Unconditionally, session-scoped rules |
| `encode`/`decode`/`jwt_decode`/`dump_storage`/`audit_security_headers`/`extract_js_endpoints` | Unconditionally |
| Navigation / actions outside any "scope" | Always allowed — the word "scope" must not appear in agent prompts or executor code |

If you find yourself writing `if (!pentestMode) return error(...)` — delete that line.
The only limits that remain are platform limits (§8) and the user-configured Intruder
thread/cap settings (user-controlled resource limits, not permission gates).

---

## 1. WHAT ALREADY EXISTS (do not rebuild)

### Agent (feature-browser/agent/)
| File | Role |
|---|---|
| `AgentEngine.kt` | ReAct loop over OpenRouter/OpenAI-compatible models. One JSON action per turn, `maxSteps` (15 → raise to 25, configurable per run), verbatim-block splicing, repeat detection, token accounting. |
| `AgentToolExecutor.kt` | ~45 tools implemented by BrowserScreen closures over the live WebView: navigate/search/scrape/scrape_structured, run_js, network (find_api_requests, get_curl, get_response_body, find_auth_flow, diff_requests, infer_schema, get_graphql_queries, list_endpoints, detect_pagination, find_rate_limits, get_cookies, export_har/postman/openapi), interaction (click_element, type_text, scroll_to, wait_for_element, take_screenshot, switch_tab), persistence (save_request, site notes, files, watches), beautify_js. |
| `AgentJs.kt` | JS snippet constants run via `evaluateJavascript`. |
| `AgentChatController.kt`, `AgentSheet.kt`, `AgentMarkdown.kt` | Chat state, bottom sheet, markdown. |

### Dev console (feature-browser/devconsole/)
| File | Role |
|---|---|
| `NetworkInterceptor.kt` | In-memory 500-entry request log from native `shouldInterceptRequest` (no bodies) + JS bridge (real headers/bodies). `NetworkRequest` model: `isApiLike`, `isPreflight`, `isGraphQl`, `authHeaderNames`, `hasLikelyAntiReplayHeaders`, `toCurl(sanitize)`, HAR export, WS frames, media detection, `InterceptorRule` engine (BLOCK/REDIRECT/ADD_HEADER). |
| `AxNetBridge.kt` + `assets/js/net_capture.js` | Hooks fetch/XHR/WS (top window + same-origin iframes), captures headers/bodies (4 KB truncation), posts JSON to native. |
| `DevConsolePanel.kt` | Compose panel: Network log, WS frames, interceptor rules, media. |

### Existing pentest-adjacent infra (reuse, don't duplicate)
- `core-data/proxy/ProxyManager.kt` — upstream proxy + rotation (HTTP/HTTPS/SOCKS).
- `feature-browser/fingerprint/FingerprintSpoofing.kt` — canvas/WebGL/hardware spoofing.
- Custom headers + user scripts (DataStore), `saved_requests` Room table, per-domain agent notes, `OkHttpProvider.kt`, Room v6 (12 entities).

---

## 2. MODULE LAYOUT (new Gradle module)

```
feature/feature-pentest/
└── src/main/kotlin/com/akay/feature/pentest/
    ├── sender/     HttpSender.kt, RawSocketClient.kt, CookieJarBridge.kt
    ├── repeater/   RepeaterViewModel.kt, CurlParser.kt
    ├── intercept/  InterceptController.kt, MatchReplace.kt
    ├── intruder/   IntruderEngine.kt, PayloadGenerators.kt, IntruderViewModel.kt
    ├── codec/      DecoderChain.kt
    ├── audit/      SecurityAuditor.kt, EndpointExtractor.kt, JwtDecoder.kt
    └── ui/         InterceptTab.kt, RepeaterTab.kt, IntruderTab.kt, DecoderTab.kt, AuditTab.kt
```

- Domain models (`HttpTransaction`, `InterceptDecision`, `FuzzJob`) in `core/core-domain/model/`.
- Room v7 in `core/core-data` (`AxDatabase` 6 → 7, `Migration(6, 7)`).
- `settings.gradle.kts`: add `include(":feature:feature-pentest")`.
- `app/build.gradle.kts` deps: `implementation(project(":feature:feature-pentest"))`.
- feature-pentest `build.gradle.kts` deps: `core-domain`, `core-data`, `core-ui`, OkHttp,
  Hilt, Compose. It must NOT depend on feature-browser (one-way: browser may call into
  pentest via interfaces defined in core-domain if needed for "Send to Repeater" glue).
- "Send to Repeater" glue: define `interface TransactionSink { suspend fun accept(t: HttpTransaction) }`
  in core-domain; feature-browser implements a thin adapter that hands captured requests to
  the RepeaterViewModel (exposed via an Activity-scoped Hilt entry or shared
  ActivityViewModel). No reverse dependency.

### Capture → tools data flow

```
net_capture.js (fetch/XHR/WS) ─┐
                               ├─► NetworkInterceptor (in-memory, live)
AxWebViewClient (native) ──────┘            │
                                            ├─► DevConsole Network tab (existing)
                                            ├─► Agent tools (existing)
                                            ├─► NEW: persist → Proxy History (Room v7, opt-in DataStore flag)
                                            ├─► NEW: InterceptController (hold/modify/drop)
                                            └─► NEW: "Send to Repeater" → HttpSender
```

---

## 3. WORKSTREAM A — AGENT: PLAYWRIGHT-MCP-SHAPED TOOLS

### 3.1 Tool surface (names match @playwright/mcp; legacy names stay as aliases)

| Tool | Input | Implementation |
|---|---|---|
| `browser_navigate` | `{url}` | existing `navigate` |
| `browser_snapshot` | `{}` | NEW PageSnapshot engine (§3.2) |
| `browser_click` | `{element, ref}` | resolve ref → CSS path → dispatch tap (§3.2) |
| `browser_type` | `{element, ref, text, submit?}` | fill + `input`/`change` events (reuses type_text internals) |
| `browser_fill_form` | `{fields:[{name,ref,value}]}` | batch browser_type in one call |
| `browser_select_option` | `{ref, values}` | set `<select>` + dispatch change |
| `browser_hover` | `{ref}` | dispatch `mouseover/mouseenter` |
| `browser_drag` | `{startRef, endRef}` | pointer/mouse event sequence |
| `browser_press_key` | `{key}` | `keydown/keypress/keyup` + `input`; `Enter` triggers submit |
| `browser_wait_for` | `{text?, textGone?, time?}` | replaces bare wait_for_element, adds text waits |
| `browser_handle_dialog` | `{accept, promptText?}` | pending WebChromeClient alert/confirm/prompt queue |
| `browser_tabs` | `{action: list/new/close/select, index?}` | extends switch_tab/list_tabs |
| `browser_evaluate` | `{function}` | existing run_js |
| `browser_console_messages` | `{}` | onConsoleMessage ring buffer (new small capture) |
| `browser_network_requests` | `{}` | existing get_network_requests, Playwright-shaped output |
| `browser_take_screenshot` | `{filename?}` | existing take_screenshot |
| `browser_file_upload` | `{paths?}` | hooks `onShowFileChooser` (WebChromeClient) |

Do NOT add `browser_pdf_save` (no printToPdf on Android WebView).

Aliases (so `agentest.md` prompts and muscle memory keep working):
`navigate, click_element, type_text, scroll_to, wait_for_element, take_screenshot,
switch_tab, list_tabs, run_js, get_network_requests` → map to the new implementations.

### 3.2 PageSnapshot engine — the core upgrade

**Problem with `click_element(selector)`:** weak models invent selectors; multi-step flows
(login → 2FA → export) break.

**New file** `agent/PageSnapshot.kt`, new constant `AgentJs.SNAPSHOT`.

1. **JS walk** of interactive DOM — buttons, links, inputs, selects, textareas,
   `[role]`, `[onclick]`, headings/labels for context — emits JSON array:
   `{ role, name (aria-label/alt/innerText), value?, ref, path }`
   - `ref` = `"s12"`-style id; `path` = unique `nth-of-type` CSS chain — the only durable
     handle across `evaluateJavascript` calls (JS object refs die between calls).
2. **Kotlin bookkeeping:** `Map<ref, path>` for the current run; invalidated on navigation
   and on `MutationObserver`-driven dirty flag (fired once per burst).
3. `browser_click {ref}` → path lookup → dispatch real `click()` + touch events for
   canvas-heavy apps → report whether navigation/DOM changed (post-check URL + dirty flag).
4. **Snapshot text for the model is compact** (role + name + ref), token-friendly; the model
   never writes selectors. Truncate to top N nodes (default 150, raise via `{maxNodes}`).

Pentest-relevant snapshot extras: flag `input[type=password]`, surface CSRF-looking hidden
fields, show `<form action>` — agent can reason about a login form without a DOM dump.

### 3.3 New agent tools (thin wrappers over Workstream B — always available)

| Tool | Behavior |
|---|---|
| `http_send` | Send raw/structured request (Repeater format), return status + headers + body (truncated). The agent's "curl but actually run it." |
| `encode` / `decode` | Chain transforms (§5.5) on a string. |
| `jwt_decode` | Split/decode JWT, flag `alg:none`, expiry. |
| `dump_storage` | localStorage/sessionStorage/cookies for current origin. |
| `audit_security_headers` | CSP/HSTS/XFO/CORS/mixed-content scorecard for current page. |
| `extract_js_endpoints` | LinkFinder-lite over same-origin scripts (§5.6). |
| `set_match_replace` / `toggle_intercept` | Manage interception from chat. |

### 3.4 Prompt + loop changes in AgentEngine

- `maxSteps` default 15 → **25**, configurable per run in AgentSheet (resource knob, not a gate).
- System prompt: add the new actions with one-line semantics + the alias list. Same JSON
  one-action-per-turn protocol (keeps free-tier OpenRouter models working).
- Token-aware snapshot truncation (top N interactive nodes).
- **Remove from the prompt** any refusal/consent/scope language planv06 added. The agent
  executes what it is told; authorization is the operator's responsibility.

---

## 4. FOUNDATIONS — Structured transaction model (build this FIRST)

### 4.1 Domain model

```kotlin
// core/core-domain/model/HttpTransaction.kt
data class HttpTransaction(
  val id: Long = 0,
  val sessionId: String,
  val method: String,
  val url: String,
  val httpVersion: String = "HTTP/1.1",
  val headers: List<Pair<String, String>>,   // ordered, duplicates allowed
  val body: ByteArray?,
  val responseStatus: Int? = null,
  val responseHeaders: List<Pair<String, String>> = emptyList(),
  val responseBody: ByteArray? = null,
  val responseTimeMs: Long = 0,
  val source: String,                         // capture | repeater | intruder | manual
  val createdAt: Long
)
```

### 4.2 Room v7 (`AxDatabase` 6 → 7)

Tables: `http_transactions`, `fuzz_jobs`, `fuzz_results`. Write a `Migration(6, 7)` and a
migration test in `core-testing` (MigrationTestHelper, pattern already in the catalog).

`NetworkInterceptor` gains `toTransaction(NetworkRequest): HttpTransaction` and an opt-in
persist hook (`HISTORY_PERSIST_ENABLED` DataStore flag, LRU-capped at ~2k rows) —
**Proxy History that survives process death**.

### 4.3 CurlParser

Parse existing saved curl strings → `HttpTransaction` (method, URL, headers, body, cookies).
Unit-test against the curls `agentest.md` workflows produce.

---

## 5. WORKSTREAM B — DEV TOOLS

### 5.1 Intercept (hold → modify → resume/drop)

- **JS side (`net_capture.js` extension):** intercept-on → the fetch/XHR wrapper parks the
  call, posts the pending request via bridge, returns a Promise resolved when native pushes
  the decision back (`evaluateJavascript` injection). 10 s auto-continue safety timeout.
- **Native:** `InterceptController` (StateFlow of pending items) + Intercept tab UI:
  editable method/URL/headers/body, Forward / Drop / Forward-unchanged.
- **Match & Replace** (extend `InterceptorRule` with `REPLACE_REQ_HEADER, REPLACE_REQ_BODY,
  REPLACE_RESP_BODY`): applied silently in the JS wrapper, zero latency. Workhorse for
  auth-header surgery and patching API responses.
- Response modification: fetch/XHR in the wrapper; subresources via `shouldInterceptRequest`
  `WebResourceResponse` replacement.
- Session-scoped (not persisted) — like Burp's default.

### 5.2 Repeater + manual Send

**HttpSender** (`feature-pentest/sender/`):
- `RawSocketClient` — manual request-line + CRLF serialization, reads raw response bytes,
  chunked decode, redirect-follow toggle, connect/read timeouts, keep-alive reuse per tab.
  Full control: custom verbs, exact header order, deliberately malformed requests — things
  OkHttp normalizes away.
- TLS via `SSLSocketFactory` with optional trust-user-CA / trust-all switch
  (`ALLOW_INSECURE_TLS` setting, default off) for routing through a desktop Burp/Charles
  upstream (ProxyManager supplies the proxy).
- `CookieJarBridge` pulls `CookieManager.getInstance().getCookie(url)` and merges a Cookie
  header (toggleable) — replays ride the browser's logged-in session.
- HttpSender is also the engine behind the agent's `http_send` tool.

**Repeater tab:** raw ↔ structured editor, Send, response pane (raw / pretty JSON),
status/length/time history per tab, diff vs previous send (reuse `diff_requests` logic).

**Entry points:** long-press "Send to Repeater" in Network log; `get_curl` result gets a
one-tap "open in Repeater" (via CurlParser); saved requests gain a structured variant.

### 5.3 Intruder-lite (parameter fuzzing)

- Positions marked `§param§` in the request editor (Burp convention).
- Engines: Sniper (v1), Clusterbomb (v2). Payload types: simple list, number range, charset
  brute force (length-capped), file import, regex-extract-from-captured-response.
- Results: payload, status, length, time, match/extract-rule hit (regex or status filter).
- Concurrency 1–4 threads (`Semaphore` on `Dispatchers.IO`), per-request delay, hard Stop,
  user-configured total-request cap (default 500). Room-persisted jobs survive navigation.

### 5.4 Decoder tab

Pure-Kotlin transform chain: URL enc/dec, Base64 enc/dec, Hex, HTML entities, Unicode
escapes, ROT13, JWT decode, epoch↔ISO time, MD5/SHA-1/SHA-256, gzip-inflate. Zero deps,
fully unit-testable.

### 5.5 Audit tab (passive, on captured data)

- Security headers scorecard per origin: CSP, HSTS, X-Content-Type-Options, X-Frame-Options,
  Referrer-Policy, Permissions-Policy, CORS misconfig (Origin echoed + credentials).
- Cookie audit: Secure/HttpOnly/SameSite from CookieManager; session-token-in-URL heuristic.
- Mixed content + exposed source maps (`*.map`) from capture history.
- `EndpointExtractor` (LinkFinder-lite): fetch same-origin JS via OkHttpProvider, regex
  endpoint strings, dedupe, rank by `isApiLike`. Feeds the UI tab and the agent tool.

### 5.6 DevConsolePanel tab layout

```
[ Network | Intercept | Repeater | Intruder | Decoder | Audit | WS | Media ]
```

Network/WS/Media unchanged; five new composables in `feature-pentest/ui/` hosted by the
existing panel shell. Agent sheet unchanged.

---

## 6. DELIVERY PHASES

```
Phase 0  HttpTransaction + Room v7 + migration test + toTransaction() + history persist + CurlParser
Phase 1  PageSnapshot + SNAPSHOT JS + ref bookkeeping + executor methods + dialog queue
         + file-chooser hook + AgentEngine prompt/maxSteps + alias mapping
Phase 2  net_capture.js hold/decision protocol + InterceptController + Intercept tab
         + MatchReplace rules
Phase 3  RawSocketClient/HttpSender/CookieJarBridge + Repeater tab + Send-to-Repeater glue
         + Decoder tab + agent http_send/encode/decode tools
Phase 4  IntruderEngine (Sniper → Clusterbomb) + payload generators + results UI
         + SecurityAuditor + Audit tab + agent audit tools (dump_storage, audit_security_headers,
         extract_js_endpoints, jwt_decode)
Phase 5  agentest.md authorized-testing prompts + docs/PENTEST_TOOLS.md + README update
```

---

## 7. TESTING STRATEGY (existing stack: JUnit5 + MockK + MockWebServer + Turbine)

| Component | Test |
|---|---|
| CurlParser, DecoderChain, JwtDecoder, MatchReplace | Pure unit tests |
| RawSocketClient / HttpSender | MockWebServer: methods, chunked bodies, redirects, trust-all option, cookie injection |
| IntruderEngine | Fake sender counting requests; Sniper/Clusterbomb expansion; stop/cap behavior |
| PageSnapshot ref resolution | Kotlin ref→path map lookup/invalidation unit tests; real-WebView behavior in androidTest |
| Room v7 migration | MigrationTestHelper |
| AgentEngine tool dispatch | MockK executor: every new tool callable with no precondition |

## 8. PLATFORM LIMITS (document in UI and docs — these are real, not gates)

1. Synthetic events ≠ real input: hover/press_key dispatch DOM events; IME-driven pages,
   `:hover` CSS, pointer-capture gestures may differ. Snapshot+click is reliable for standard controls.
2. Cross-origin iframes invisible (same-origin only in net_capture.js).
3. Intercept covers fetch/XHR + subresources; main-document POSTs can't be modified (platform).
4. Web Worker traffic invisible to both hooks.
5. No out-of-band collaborator (needs a server) — blind-XSS/SSRF callbacks out of scope on-device.
6. Trust-all TLS is a deliberate footgun: default off, red warning, operator's call.

## 9. ACCEPTANCE CRITERIA

- Agent completes login → navigate → export using only `browser_snapshot`/`browser_click`/`browser_type` refs (no model-written selectors).
- Captured POST: held in Intercept → header edited → forwarded; page continues or drops cleanly.
- Captured request → Repeater → replayed with live session cookie; status/length/time history + diff vs previous.
- Intruder job: 200 payloads, ≤4 threads, stoppable, results persist.
- `http_send` from chat works against any host with no precondition, prompt, or confirmation.
- `./gradlew :app:assembleDebug` and `./gradlew test` pass in CI with the new module.

---

*Plan Version: 7.0 — AxBrowser · Status: Ready for Implementation · No gating: all tools always available.*
