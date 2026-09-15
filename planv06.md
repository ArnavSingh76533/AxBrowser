# AxBrowser — Plan v6
# Pentest-Grade Agent Tooling (Playwright-MCP-shaped) + Burp-Suite-like Dev Tools

---

## 1. Context — What Exists Today (grounded in this repo)

The agent and dev console are already substantial. This plan upgrades them; it does not
start from scratch.

### Agent (feature-browser/agent/)
| File | Role today |
|---|---|
| `AgentEngine.kt` | ReAct loop over OpenRouter/OpenAI-compatible chat models. One JSON action per turn, `maxSteps=15`, verbatim-block splicing (get_curl etc.), repeat detection, token accounting. |
| `AgentToolExecutor.kt` | Interface of ~45 tools implemented by BrowserScreen closures over the live WebView: navigate/search/scrape/scrape_structured, run_js, network inspection (find_api_requests, get_curl, get_response_body, find_auth_flow, diff_requests, infer_schema, get_graphql_queries, list_endpoints, detect_pagination, find_rate_limits, get_cookies, export_har/postman/openapi), interaction (click_element, type_text, scroll_to, wait_for_element, take_screenshot, switch_tab), persistence (save_request, site notes, files, watches), beautify_js. |
| `AgentJs.kt` | JS snippets run via `evaluateJavascript` (get_page_text, get_links, scrape, …). |
| `AgentChatController.kt` / `AgentSheet.kt` / `AgentMarkdown.kt` | Chat state, bottom-sheet UI, markdown rendering. |

### Dev console (feature-browser/devconsole/)
| File | Role today |
|---|---|
| `NetworkInterceptor.kt` | In-memory (500-entry) request log fed from two sources: native `shouldInterceptRequest` (no bodies) and the JS bridge (real headers/bodies). Rich `NetworkRequest` model: `isApiLike`, `isPreflight`, `isGraphQl`, `authHeaderNames`, `hasLikelyAntiReplayHeaders`, `toCurl(sanitize)`, HAR 1.2 export, WebSocket frames, detected media, and a simple `InterceptorRule` engine (BLOCK / REDIRECT / ADD_HEADER). |
| `AxNetBridge.kt` + `assets/js/net_capture.js` | Hooks `fetch`/XHR/WebSocket (top window + same-origin iframes) in-page, captures request/response headers and bodies (4 KB truncation), posts JSON to native. |
| `DevConsolePanel.kt` | Compose panel: network log, WS frames, interceptor rules, media. |

### Already-present pentest-adjacent infrastructure
- `core-data/proxy/ProxyManager.kt` — upstream proxy config + rotation (HTTP/HTTPS/SOCKS).
- `feature-browser/fingerprint/FingerprintSpoofing.kt` — canvas/WebGL/hardware spoofing.
- Custom headers + user scripts (DataStore), saved curl bookmarks (`saved_requests` Room table), per-domain agent notes.
- `OkHttpProvider.kt` singleton; Room v6 with 12 entities.

---

## 2. Goals & Non-Goals

### Goals
1. **Agent toolset upgraded to Playwright-MCP shape**: the same tool names and semantics as
   `@playwright/mcp` (snapshot-with-refs, click/type/fill_form/select_option/hover/press_key/
   wait_for/tabs/handle_dialog/evaluate/…), so the agent drives the browser like Playwright
   would — more reliable than CSS-selector guessing, better for multi-step pentest flows.
2. **More useful for pentestors**: raw HTTP send/replay, request interception (hold-modify-
   resume), match & replace, parameter fuzzing (Intruder-lite), encoder/decoder, security
   audits, storage/cookie inspection — with explicit consent + scope guardrails.
3. **Dev console becomes Burp-like**: Proxy history (persisted), Intercept, Repeater/"Send",
   Intruder, Decoder, Audit tabs inside the existing DevConsolePanel shell.

### Non-Goals (be honest about platform limits)
- **Real Playwright on device.** Playwright/CDP requires a Chromium with a devtools socket;
   stock Android `WebView` exposes none. We implement Playwright-**API-shape** on our own
   WebView engine (same tool names/behavior for the model), not the Playwright runtime.
   A remote "agent orchestrates desktop Playwright" mode is a possible later add-on, not in
   this plan's scope.
- **Modifying the main-frame document POST.** Android's native intercept can't read or rewrite
   the main document request. Intercept/modify targets fetch/XHR + subresources (that's where
   APIs live anyway — consistent with the existing JS-bridge design).
- **Web Worker traffic.** Workers are invisible to both hooks (already a known, documented limit).
- **Out-of-band collaborator (SSRF/blind-XSS callbacks).** Needs a server; out of scope on-device.

---

## 3. Architecture

New Gradle module, following the existing Clean-Architecture layout:

```
feature/feature-pentest/
└── src/main/kotlin/com/akay/feature/pentest/
    ├── sender/        HttpSender.kt, RawSocketClient.kt, CookieJarBridge.kt
    ├── repeater/      RepeaterViewModel.kt, CurlParser.kt
    ├── intercept/     InterceptController.kt, MatchReplace.kt
    ├── intruder/      IntruderEngine.kt, PayloadGenerators.kt, IntruderViewModel.kt
    ├── codec/         DecoderChain.kt (pure Kotlin)
    ├── audit/         SecurityAuditor.kt, EndpointExtractor.kt, JwtDecoder.kt
    └── ui/            InterceptTab.kt, RepeaterTab.kt, IntruderTab.kt, DecoderTab.kt, AuditTab.kt
```

- **Domain models** (`HttpTransaction`, `InterceptDecision`, `FuzzJob`) live in
  `core/core-domain/model/`; Room persistence in `core/core-data` (DB v7) so feature modules
  stay decoupled, matching the existing pattern.
- **feature-browser** keeps capture (NetworkInterceptor, AxNetBridge) and exposes it;
  **feature-pentest** consumes the captured log + adds active tooling. `app` wires both
  (settings.gradle.kts + app deps).
- **No new third-party dependencies.** Everything is OkHttp (already 4.12.0), raw
  `java.net.Socket`/`SSLSocket` for raw HTTP control, java.security for hashing, and Compose
  for UI. This keeps the plan buildable in CI as-is.

### Data flow (capture → tools)

```
net_capture.js (fetch/XHR/WS) ─┐
                               ├─► NetworkInterceptor (in-memory, live)
AxWebViewClient (native) ──────┘            │
                                            ├─► DevConsole Network tab (read-only, today)
                                            ├─► Agent tools (get_curl etc., today)
                                            └─► NEW: Persist → Proxy History (Room, opt-in)
                                                 NEW: InterceptController (hold/modify)
                                                 NEW: "Send to Repeater" → HttpSender
```

---

## 4. Workstream A — Agent: Playwright-MCP-Shaped Tools

### 4.1 New tool surface (names match @playwright/mcp; legacy names stay as aliases)

Add to `AgentToolExecutor` + AgentEngine system prompt (one action per turn, JSON protocol
unchanged — that's what keeps free-tier OpenRouter models working):

| Playwright-MCP name | Input | Implementation |
|---|---|---|
| `browser_navigate` | `{url}` | existing `navigate` |
| `browser_snapshot` | `{}` | **NEW PageSnapshot engine** (below) |
| `browser_click` | `{element, ref}` | resolve ref → CSS path → dispatch tap (see 4.2) |
| `browser_type` | `{element, ref, text, submit?}` | fill + proper `input`/`change` events (reuses type_text internals) |
| `browser_fill_form` | `{fields:[{name,ref,value}]}` | batch of browser_type in one call |
| `browser_select_option` | `{ref, values}` | set `<select>` + dispatch change |
| `browser_hover` | `{ref}` | dispatch `mouseover/mouseenter` (synthetic-event caveat in §8) |
| `browser_drag` | `{startRef, endRef}` | pointer/mouse event sequence |
| `browser_press_key` | `{key}` | `keydown/keypress/keyup` + `input` (`Enter` triggers submit) |
| `browser_wait_for` | `{text?, textGone?, time?}` | replaces bare wait_for_element, adds text waits |
| `browser_handle_dialog` | `{accept, promptText?}` | pending WebChromeClient alert/confirm/prompt queue |
| `browser_tabs` | `{action: list/new/close/select, index?}` | extends switch_tab/list_tabs |
| `browser_evaluate` | `{function}` | existing run_js (sandboxed function body) |
| `browser_console_messages` | `{}` | onConsoleMessage ring buffer (new small capture) |
| `browser_network_requests` | `{}` | existing get_network_requests, Playwright-shaped output |
| `browser_take_screenshot` | `{filename?}` | existing take_screenshot |
| `browser_file_upload` | `{paths?}` | hooks `onShowFileChooser` (WebChromeClient) |
| `browser_pdf_save` | `{}` | `printToPdf`? Not on WebView — **omit**, keep out of prompt |

Aliases so existing prompts/tests (`agentest.md`) and muscle memory keep working:
`navigate, click_element, type_text, scroll_to, wait_for_element, take_screenshot,
switch_tab, list_tabs, run_js, get_network_requests` map onto the new implementation.

### 4.2 PageSnapshot engine (the core upgrade — replaces "guess a selector")

**Problem with today's `click_element(selector)`:** weak models invent selectors that don't
exist; multi-step flows (login → 2FA → export) break.

**New files:** `agent/PageSnapshot.kt` (Kotlin model + ref bookkeeping), one new constant in
`AgentJs.kt` (`SNAPSHOT`).

Design:
1. JS walk of the interactive DOM (buttons, links, inputs, selects, textareas, `[role]`,
   `[onclick]`, headings/labels for context) → JSON array:
   `{ role, name (accessible name: aria-label/alt/innerText), value?, ref, path }`.
   - `ref` = `"s12"`-style id; `path` = a **unique CSS path** (`nth-of-type` chain) — the only
     durable handle across `evaluateJavascript` calls (JS object refs die between calls).
2. Kotlin keeps `Map<ref, path>` for the current run; invalidated on navigation and on a
   `MutationObserver`-driven dirty flag (fired once per burst).
3. `browser_click {ref}` → looks up path → dispatches a real `click()` (plus touch events for
   canvas-heavy apps) → returns whether navigation/DOM changed (post-check via URL + dirty flag).
4. Snapshot output given to the model is compact (role + name + ref), like Playwright's
   `browser_snapshot` — token-friendly, and the model never writes selectors.

Pentest-relevant extras folded into snapshot: `input[type=password]` flagged, CSRF-looking
hidden fields surfaced, `<form action>` shown — so the agent can reason about a login form
without dumping the whole DOM.

### 4.3 Pentest-agent hardening

- **maxSteps**: raise default 15 → 25 for pentest runs (configurable per run in AgentSheet).
- **New agent tools** (thin wrappers over Workstream B, added to the same executor):
  - `http_send` — send a raw/structured request (from Repeater format) and return
    status + headers + body (truncated). This is the agent's "curl but actually run it" tool.
  - `encode` / `decode` — chain transforms (§5.5) on a string.
  - `jwt_decode` — split/decode JWT, flag `alg:none`, expiry.
  - `dump_storage` — localStorage/sessionStorage/cookies for current origin (+ flags).
  - `audit_security_headers` — CSP/HSTS/XFO/CORS/mixed-content scorecard for current page.
  - `extract_js_endpoints` — LinkFinder-lite over same-origin scripts (see §5.6).
  - `set_match_replace` / `toggle_intercept` — manage interception from chat.
- **Guardrails (non-negotiable):**
  - **Pentest Mode** toggle in Settings (DataStore: `PENTEST_MODE`, `PENTEST_CONSENT_ACK`).
    Active-tooling (`http_send`, intruder, intercept-modify) is rejected unless enabled +
    consent acknowledged ("I am authorized to test these targets").
  - **Scope list** (`SCOPE_HOSTS_JSON`): agent refuses navigation/actions outside scope when
    Pentest Mode is on; tools that send traffic double-check scope at the executor level
    (not just in the prompt — prompts are not enforcement).
  - Destructive verbs (`DELETE/PUT/PATCH` replays, fuzzing) require one-tap confirm in the
    chat sheet, surfaced as an `AgentEvent.ConfirmRequired` (new event type).

---

## 5. Workstream B — Dev Tools: Burp-Suite-Like

### 5.1 Structured transaction model (foundation for everything below)

Today `saved_requests` stores only a curl **string**. Repeater/fuzzing need structure:

```kotlin
// core/core-domain/model/HttpTransaction.kt
data class HttpTransaction(
  val id: Long = 0,
  val sessionId: String,          // browsing/pentest session grouping
  val method: String,
  val url: String,
  val httpVersion: String = "HTTP/1.1",
  val headers: List<Pair<String, String>>,  // ordered, duplicates allowed
  val body: ByteArray?,
  val responseStatus: Int? = null,
  val responseHeaders: List<Pair<String, String>> = emptyList(),
  val responseBody: ByteArray? = null,
  val responseTimeMs: Long = 0,
  val source: String,             // capture | repeater | intruder | manual
  val createdAt: Long
)
```

Room v7 (`AxDatabase` version 6 → 7, `Migration(6,7)`): `http_transactions`, `fuzz_jobs`,
`fuzz_results`. Migration test in `core-testing`.

`NetworkInterceptor` gains `toTransaction(NetworkRequest): HttpTransaction` and an opt-in
persist hook (`HISTORY_PERSIST_ENABLED`, capped, e.g. 2k rows LRU) — **Proxy History that
survives process death**, the single most-requested Burp behavior missing today.

### 5.2 Intercept (hold → modify → resume/drop)

- **JS side (`net_capture.js` extension):** when intercept-on, the fetch/XHR wrapper parks the
  call, posts the pending request via bridge, and returns a Promise resolved when native
  pushes the decision back (`evaluateJavascript` injection; 10 s auto-continue safety timeout,
  matching today's defensive style).
- **Native:** `InterceptController` (StateFlow of pending items) + UI in the new Intercept tab:
  editable method/URL/headers/body, Forward / Drop / forward-without-change.
- **Match & Replace rules** (extend the existing `InterceptorRule` enum with
  `REPLACE_REQ_HEADER, REPLACE_REQ_BODY, REPLACE_RESP_BODY`): applied silently in the JS
  wrapper with zero latency — the workhorse for adding/removing auth headers, patching API
  responses, bypassing client-side checks during authorized testing.
- Response-side modification for fetch/XHR happens in the wrapper; subresource response
  modification via `shouldInterceptRequest` `WebResourceResponse` replacement.
- Intercept + rules are session-scoped (not persisted), like Burp's default.

### 5.3 Repeater + manual "Send"

- **HttpSender** (`feature-pentest/sender/`): raw-socket HTTP/1.1 client for full control
  (custom verbs, exact header order, deliberately malformed requests — things OkHttp normalizes):
  - `RawSocketClient` — manual request-line + CRLF serialization, reads raw response bytes,
    chunked-encoding decode, redirect-follow toggle, connect/read timeouts, HTTP/1.1 keep-alive
    reuse per Repeater tab.
  - TLS: `SSLSocketFactory` with **optional trust-user-CA / trust-all** switch
    (`ALLOW_INSECURE_TLS`, default off, loud warning) — required when routing through a desktop
    Burp/Charles upstream (ProxyManager already supplies the proxy).
  - **Session reuse:** `CookieJarBridge` pulls `CookieManager.getInstance().getCookie(url)`
    and merges as Cookie header (flag to disable), so replays ride the browser's logged-in
    session — the whole point vs. plain curl.
- **Repeater tab:** request editor (Raw text ↔ structured tabs), Send, response pane
  (raw / pretty JSON toggle), status/length/time column history per tab, **diff vs previous
  send** (reuses the diff logic already in `diff_requests`).
- **Entry points:** "Send to Repeater" long-press in Network log; agent `get_curl` result gets
  a one-tap "open in Repeater" (via `CurlParser`); saved requests gain a structured variant.
- `HttpSender` is also the engine behind the agent's `http_send` tool (§4.3).

### 5.4 Intruder-lite (parameter fuzzing)

- Positions marked `§param§` in the request editor (Burp convention).
- Engines: **Sniper** (v1), **Clusterbomb** (v2). Payload types: simple list, number range,
  charset brute force (length-capped), file import, regex-extract-from-captured-response
  (e.g., pull IDs from a listing, then fuzz each).
- Results table: payload, status, length, time, match/extract-rule hit (regex or status filter).
- Concurrency capped 1–4 threads (`Semaphore` on `Dispatchers.IO`), per-request delay,
  hard Stop, and total-request cap (default 500) — mobile battery/network reality.
- Room-persisted jobs so a long run survives navigation.

### 5.5 Decoder tab

Pure-Kotlin transform chain (stack like Burp): URL enc/dec, Base64 enc/dec, Hex, HTML entities,
Unicode escapes, ROT13, JWT decode, epoch↔ISO time, MD5/SHA-1/SHA-256, gzip-inflate for
response bodies. Zero dependencies; fully unit-testable.

### 5.6 Audit tab (passive, on captured data — no active attacks)

- **Security headers scorecard** per origin: CSP, HSTS, X-Content-Type-Options, X-Frame-Options,
  Referrer-Policy, Permissions-Policy, CORS reflection misconfig (`Origin` echoed with
  `Access-Control-Allow-Credentials: true` — checkable from already-captured headers).
- **Cookie audit**: Secure/HttpOnly/SameSite flags from CookieManager; session-token-in-URL heuristic.
- **Mixed content + exposed source maps** (`*.map` requests) from capture history.
- **EndpointExtractor (LinkFinder-lite)**: fetch same-origin JS (via existing OkHttp provider),
  regex absolute/relative endpoint strings, dedupe, rank by API-likeness (reuse
  `NetworkRequest.isApiLike` heuristics). Feeds both the UI tab and the agent tool.

### 5.7 DevConsolePanel tab layout

```
[ Network | Intercept | Repeater | Intruder | Decoder | Audit | WS | Media ]
```
Network/WS/Media stay as-is; the five new tabs are new composables in `feature-pentest/ui/`,
hosted by the existing panel shell. Agent sheet unchanged.

---

## 6. Delivery Phases

### Phase 0 — Foundations (small, unblocks everything)
- [ ] `HttpTransaction` model + Room v7 entities + migration (+ migration test)
- [ ] `NetworkInterceptor.toTransaction()` + opt-in Proxy-History persistence
- [ ] `CurlParser` (parse existing saved curls → transactions)

### Phase 1 — Agent: Playwright-shaped toolset
- [ ] `PageSnapshot.kt` + `AgentJs.SNAPSHOT` + ref bookkeeping + MutationObserver dirty flag
- [ ] New executor methods: snapshot/click-by-ref/fill_form/select_option/hover/drag/
      press_key/wait_for(text)/handle_dialog/tabs/evaluate/console/file_upload
- [ ] Dialog queue wiring in `AxWebChromeClient`; file-chooser hook
- [ ] AgentEngine prompt: new actions + aliases; maxSteps 25 (configurable); token-aware
      snapshot truncation (top N interactive nodes)
- [ ] Legacy alias mapping so `agentest.md` prompts still pass

### Phase 2 — Intercept + Match&Replace
- [ ] `net_capture.js`: hold/decision protocol + rule application
- [ ] `InterceptController` + Intercept tab UI + auto-continue timeout
- [ ] `InterceptorRule` enum extension + rule editor additions

### Phase 3 — Repeater + Send + Decoder
- [ ] `RawSocketClient`/`HttpSender`/`CookieJarBridge` (+ insecure-TLS switch)
- [ ] Repeater tab (raw/structured editor, response panes, history, diff)
- [ ] "Send to Repeater" from Network log + agent `get_curl`
- [ ] Decoder tab + transform chain
- [ ] Agent `http_send` + `encode/decode` tools (gated on Pentest Mode)

### Phase 4 — Intruder-lite + Audit
- [ ] `IntruderEngine` (Sniper, then Clusterbomb) + payload generators + results UI
- [ ] `SecurityAuditor` + Audit tab + agent audit tools (`dump_storage`,
      `audit_security_headers`, `extract_js_endpoints`, `jwt_decode`)
- [ ] Scope enforcement + consent UX finalized (Pentest Mode banner in chat sheet)

### Phase 5 — Polish
- [ ] New prompts section in `agentest.md` (authorized-testing recipes)
- [ ] Docs: `docs/PENTEST_TOOLS.md` (capabilities + limits + consent model)
- [ ] README feature list update

---

## 7. Testing Strategy (matches existing stack: JUnit5 + MockK + MockWebServer + Turbine)

| Component | Test |
|---|---|
| `CurlParser`, `DecoderChain`, `JwtDecoder`, `MatchReplace` | Pure unit tests |
| `RawSocketClient`/`HttpSender` | `MockWebServer` (methods, chunked bodies, redirects, bad TLS option, cookie injection) |
| `IntruderEngine` | Fake sender counting requests; Sniper/Clusterbomb expansion; cap/stop behavior |
| `PageSnapshot` ref resolution | JS fixtures run through JS engine is out of scope for JVM unit tests → model the ref→path map in Kotlin and test lookup/invalidation; real-WebView behavior in instrumented tests (androidTest) |
| Room v7 migration | `room-testing` MigrationTestHelper (pattern already in catalog) |
| AgentEngine guardrails | MockK executor: out-of-scope host → rejection event; confirm-required flow via Turbine |

---

## 8. Risks & Known Limits (put these in the UI where relevant)

1. **Synthetic events ≠ real input**: hover/press_key dispatch DOM events; pages relying on
   real IME input, `:hover` CSS, or pointer-capture gestures may not respond identically.
   Snapshot+click remains reliable for standard controls.
2. **Cross-origin iframes** stay invisible (same-origin only in `net_capture.js` today) — document it.
3. **Intercept scope**: fetch/XHR + subresources only; main-document POSTs can't be modified
   (platform limit, stated in §2).
4. **Trust-all TLS** is a deliberate footgun: default-off, Settings-gated, red warning.
5. **Legal/ethical**: all active tooling behind Pentest Mode + scope + consent. The app keeps
   its existing stance (same spirit as the note at the bottom of `agentest.md`).

## 9. Acceptance Criteria

- Agent can complete a full login → navigate → export flow using only
  `browser_snapshot`/`browser_click`/`browser_type` refs (no CSS selectors from the model).
- A captured POST can be: held in Intercept → header edited → forwarded, and the page
  continues normally (or drops cleanly).
- A captured request can be sent to Repeater and replayed with the live session cookie,
  with status/length/time history and diff vs previous send.
- Intruder job of 200 payloads runs with ≤4 threads, can be stopped, results persist.
- Out-of-scope host access is blocked at the executor with a clear agent-visible error.
- `./gradlew :app:assembleDebug` and `./gradlew test` pass in CI with the new module.
