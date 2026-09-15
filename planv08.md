# AxBrowser — Build Prompt v8
# Agent v8 (task lists, human handoff, captcha, real body capture) + Bug-Bounty Workbench
# Execution edition. Supersedes planv07.md / planv07-phase01.md.
# Still no gating: no Pentest Mode toggle, no scope list, no consent flow.

---

## 0. WHY v8 — WHAT v7 SHIPPED AND WHAT IT MISSED

v7 built real engines (PageSnapshot, HttpSender, Intruder, Decoder, SecurityAuditor)
and real agent tools, but a pass over the actual code shows four gaps that make the
product feel unchanged in daily use. v8 closes them and adds the bug-bounty layer.

| # | Gap found in code | Evidence | v8 fix |
|---|---|---|---|
| 1 | **Request/response BODIES were almost never captured.** `net_capture.js` — the only thing that can read a POST body on Android — was injected *only* inside `if (erudaEnabledState.value)`. With Eruda off (the default) users get body-less `shouldInterceptRequest` rows forever. | `BrowserScreen.kt` `onPageStarted { if (erudaEnabledState.value) { ...net_capture.js... } }` | Always inject on every document start (§2) |
| 2 | **Intercept was dead code.** `InterceptController.hold()` had zero callers and `PushChannel.register()` was never called, so the Intercept toggle held nothing. | repo-wide grep: only the definition | Real hold/decide protocol + WebView pusher wired (§3) |
| 3 | **The agent could not see its own new tools.** `AgentEngine.systemPrompt` lists only the legacy tool names — `browser_snapshot`, `http_send`, `encode`, `audit_security_headers`, `send_to_repeater`… were never documented, so a model can't call what it can't read. | `AgentEngine.kt` prompt has no v7 entry | Prompt rewritten from the executor interface (§6) |
| 4 | **No multi-step memory.** A 20-step pentest run was a single flat ReAct loop; the model re-derived its plan every turn and the user saw no progress. | `AgentEngine.run()` — no plan state | `todo_write` / `todo_read` + on-screen checklist (§5) |

Plus three genuinely new capabilities aimed at the two audiences (§7, §8, §9):

- **Captcha workbench** — detect, native-tap, token-inject, or hand off to a solver service.
- **Human handoff** — `ask_user`, so the agent can pause for 2FA / a slider captcha / a
  decision and resume with full history instead of dying at a wall.
- **Findings + report export** — bug-bounty output, not just raw captures.

---

## 1. GROUND RULES (unchanged from v7)

1. **Do not rewrite what works.** Extend `feature-browser/agent/`, `feature-browser/devconsole/`,
   `feature/feature-pentest/`. Create new files only where listed.
2. **No gating.** No tool checks a setting, asks, or confirms. Authorization is the operator's job.
3. All async is Coroutines + Flow. All UI is Jetpack Compose. No XML, no RxJava.
4. **Write every file completely.** No `// TODO` stubs.
5. **No new third-party dependencies.** OkHttp 4.12, Compose, `android.webkit`. Anything else
   (a captcha SDK, an OCR engine, a headless browser) is out of scope by construction.

---

## 2. WORKSTREAM A — BODY CAPTURE, FOR REAL

### 2.1 The injection fix (one line, biggest single win)

`net_capture.js` is the *only* capture path that can see bodies: Android's
`shouldInterceptRequest` hands you a `WebResourceRequest` with **no body API at all**.
So it must run on **every** page load, unconditionally, at document start — not behind
a dev-tools flag.

```
onPageStarted { url ->
    // always, before any user script or Eruda:
    evaluateJavascript(assets["js/net_capture.js"], null)
}
```

Eruda keeps its own separate injection (it's a UI, not a capture engine).

### 2.2 `net_capture.js` upgrades (assets, no compile risk)

| Capture path | v7 | v8 |
|---|---|---|
| `fetch` string/FormData/Blob/ArrayBuffer/Request body | yes | yes (kept) |
| `fetch` **streaming / SSE response** | `clone().text()` **never resolves** → body silently lost, clone leaked | content-type check; `text/event-stream` → read chunks off the clone, post each frame, cap at N frames |
| `XMLHttpRequest` text response | yes | yes |
| `XMLHttpRequest` `responseType: json / arraybuffer / blob / document` | **dropped** (only `''`/`text` read) | all decoded: json→`JSON.stringify`, arraybuffer→TextDecoder, blob→size marker, document→serialized |
| XHR with `responseType='json'` on modern JSON APIs | empty body | full body |
| `navigator.sendBeacon` | not hooked | hooked (analytics/tracking endpoints bug hunters actually need) |
| `EventSource` (SSE) | not hooked | hooked: open + each message + error |
| Worker / same-origin iframe fetch | yes | yes (kept) |
| Native `<form>` submit | yes (JS-side fields) | yes (kept) |

Every new path routes through the same `post()` bridge, so `NetworkInterceptor`,
the agent tools, HAR/Postman/OpenAPI export and the Proxy History all get the
richer data with no native changes.

### 2.3 Backpressure and honesty

- Clones are dropped the moment the response is not a body we can read (streams we skip).
- A body that genuinely cannot be read is logged with an explicit
  `[streamed body - not capturable]` / `[binary body]` marker rather than an empty
  string, and `get_curl` already surfaces that marker to the user verbatim.

---

## 3. WORKSTREAM B — INTERCEPT THAT ACTUALLY HOLDS REQUESTS

v7 documented the protocol; v8 implements both halves.

### 3.1 JS side (`net_capture.js`)

```
window.__axSetIntercept(bool)          <- native pushes enable state
window.__axSetMatchReplace(jsonArray)  <- native pushes rules
window.__axInterceptDecide(json)       <- native pushes a decision for a parked id
```

- `fetch`/XHR call `parkAndWait({url, method, headers, body})` when intercept is on:
  it posts the request via `AxNet.hold(...)`, returns a Promise, and the request is
  **not** sent until `__axInterceptDecide` resolves it.
- Decision shapes: `{action:"forward", url, method, headers, body}` or `{action:"drop"}`.
- `drop` rejects the fetch promise / aborts the XHR (the honest Burp semantic), so the
  page sees a network error and the operator sees why.
- **120 s safety timeout**: if native never answers, the request is forwarded unchanged
  so a forgotten tab can't hang a site forever.
- Match&Replace rules apply silently in the wrapper (zero latency) for
  `REPLACE_REQ_HEADER` / `REPLACE_REQ_BODY` / `REPLACE_RESP_BODY`.

### 3.2 Native side

- `AxNetBridge.hold(json)` → `InterceptController.hold(id, url, method, headers, body)`.
- `PushChannel.register { json -> webView.evaluateJavascript("__axInterceptDecide('<json>')") }`
  registered in the `AndroidView` factory, unregistered in `onDispose`.
- `LaunchedEffect` mirrors `InterceptController.enabled` and `.rules` into the page on
  every change, so the switch in the Intercept tab takes effect immediately and rules
  apply to in-flight pages.
- The existing Intercept tab (edit method/URL/headers/body → Forward / Drop) becomes
  live with **no UI change** — it was already built against this exact controller.

---

## 4. WORKSTREAM C — CAPTCHA WORKBENCH

Two audiences, two entry points: the agent (`captcha_detect` / `captcha_solve`) and a
new **Captcha** tab in Dev Tools.

### 4.1 Detect (`captcha/CaptchaDetector.kt`)

One JS walk returns every captcha on the page with what's needed to act on it:

```json
{"url":"...","widgets":[
  {"type":"recaptcha_v2","provider":"google","sitekey":"6Lc...","selector":".g-recaptcha",
   "tapX":34,"tapY":412,"kind":"checkbox","solved":false},
  {"type":"turnstile","provider":"cloudflare","sitekey":"0x4...","kind":"checkbox", "...":""},
  {"type":"image_grid","provider":"google","kind":"challenge"},
  {"type":"slider","provider":"unknown","kind":"interactive"},
  {"type":"text_captcha","provider":"unknown","kind":"ocr"}
]}
```

Detection covers: reCAPTCHA v2 (anchor iframe + `.g-recaptcha[data-sitekey]`),
reCAPTCHA v3 (sitekey sniffed out of `___grecaptcha_cfg.clients`, plus "a `grecaptcha`
exists but no visible widget" as a v3 signal), hCaptcha, Cloudflare Turnstile,
Cloudflare interstitial (`Just a moment…`), GeeTest, slider/puzzle widgets and plain
image/text captchas. `tapX`/`tapY` are viewport-relative CSS pixels of the checkbox,
computed per provider, so the native tap lands on it.

### 4.2 Solve — three escalating strategies

1. **Native tap** (works with no config at all). `browser_tap {x, y}` dispatches a real
   `ACTION_DOWN`/`ACTION_UP` pair into the WebView via `dispatchTouchEvent`, converting
   CSS px → view px with the display density. This is the only way to reach a
   cross-origin iframe's checkbox — same-document `click()` cannot. After tapping,
   re-detect: a low-risk score means the challenge just resolves.
2. **Solver service** (opt-in, needs a key). `CaptchaSolver` speaks the
   **2Captcha-compatible API** (`/in.php` + `/res.php`), so 2captcha, anti-captcha-style
   mirrors and self-hosted gateways all work by changing the base URL. `solve()` returns
   a token, `tokenInjectionJs()` writes it into `g-recaptcha-response` /
   `h-captcha-response` / `cf-turnstile-response` and fires the registered callback
   (best-effort `___grecaptcha_cfg.clients` walk for reCAPTCHA).
3. **Human handoff** (§5.2). Anything left (image grids, sliders, OCR captchas, 2FA)
   is not solvable on-device without a service and an OCR model — the agent says so and
   uses `ask_user` instead of pretending.

### 4.3 Settings

New preferences: `captcha_solver_enabled`, `captcha_solver_provider`,
`captcha_solver_base_url`, `captcha_solver_api_key_enc` (encrypted like the AI key),
`captcha_auto_checkbox`. Exposed through `BrowserViewModel` StateFlows; the Captcha tab
writes them. **Zero config is a valid configuration** — with no key, strategies 1 and 3
still work.

---

## 5. WORKSTREAM D — AGENT v8: PLAN, PAUSE, RESUME

### 5.1 The task list (the "give the agent a to-do list" ask)

- `todo_write {"items":[{"text":"Log in as the test user","status":"in_progress"}, …]}`
  replaces the whole list; `todo_read` echoes it.
- State lives in `AgentTodoList` (`mutableStateListOf`), owned by `AgentChatController`
  and rendered as a live progress checklist in the agent sheet — so the operator watches
  the agent work through the plan instead of guessing.
- The prompt makes it mandatory for multi-step work: *write the list first, mark one item
  `in_progress`, flip it to `done` when it lands, rewrite the list if the plan changes.*

### 5.2 `ask_user` — human-in-the-loop without losing the thread

```
{"action":"ask_user","action_input":{"question":"A slider captcha is blocking login. Solve it in the browser, then reply 'done'."}}
```

The engine ends the turn with the question as its answer. Because
`AgentChatController` caches the engine, **the whole ReAct history survives** — the
user's next message continues the same run at full context. This is what makes
captcha/2FA/OTP/manual-approval flows workable on a phone.

### 5.3 Prompt rewrite

The system prompt is rebuilt from the executor interface so every callable action is
documented: the Playwright-shaped set (`browser_snapshot` → ref-based
`browser_click`/`browser_type`/`browser_fill_form`/`browser_select_option`/`browser_hover`/
`browser_press_key`/`browser_wait_for`/`browser_handle_dialog`/`browser_tabs`/
`browser_console_messages`), the pentest set (`http_send`, `encode`/`decode`,
`jwt_decode`, `dump_storage`, `audit_security_headers`, `extract_js_endpoints`,
`toggle_intercept`, `set_match_replace`, `send_to_repeater`), and the v8 additions.
`maxSteps` default 15 → **25**.

---

## 6. WORKSTREAM E — BUG-BOUNTY FINDINGS

- `FindingsStore`: `Finding(id, title, severity, url, description, evidence, createdAt)` in a
  `StateFlow`, persisted to `AxStorage` (`agent/findings.json`) so a session survives
  process death, reloaded on startup.
- Agent tools: `save_finding`, `list_findings`, `export_bug_report {"program","platform"}`.
- Dev Tools gains a **Findings** tab: severity-chipped list, tap for full detail, per-finding
  copy, and one-tap report export.
- `export_bug_report` writes a Markdown report — title, severity, affected URL, reproduction
  (the captured curl, sanitized), impact, remediation — shaped to paste straight into
  HackerOne/Bugcrowd/YesWeHack. **Sanitized by default**: a report is a document you share,
  so live cookies/tokens never land in it unless explicitly asked for.

---

## 7. DELIVERY PHASES

```
Phase 0 (v7, done)  HttpTransaction + Room v7 + CurlParser + PageSnapshot + pentest module
Phase 1 (v7, done)  Playwright-shaped executor methods + dialog queue + file chooser + 5 tabs
Phase 2 (v8)  A: always-on net_capture.js + XHR json/blob + SSE + sendBeacon
              B: intercept hold/decide protocol + PushChannel + AxNetBridge.hold + state sync
Phase 3 (v8)  D: AgentTodoList + todo_write/todo_read + sheet checklist + ask_user
              E: AgentEngine prompt rewrite (documents every v7/v8 action) + maxSteps 25
Phase 4 (v8)  C: CaptchaDetector + native browser_tap + CaptchaSolver + Captcha tab + settings
Phase 5 (v8)  F: FindingsStore + save_finding/list_findings/export_bug_report + Findings tab
Phase 6  agentest.md bug-bounty prompt pack + docs/PENTEST_TOOLS.md + README
```

---

## 8. PLATFORM LIMITS (documented, real, not gates)

1. Synthetic DOM events ≠ real input; `:hover` CSS and IME-driven pages can still differ.
   Native `browser_tap` exists precisely because synthetic clicks fail on canvas and
   cross-origin iframes.
2. Cross-origin iframes are invisible to `net_capture.js`; only the top document and
   same-origin frames are captured.
3. Main-document POST bodies still cannot be read natively — the JS bridge is the only
   path, which is why §2.1 matters so much.
4. Web Worker traffic is captured best-effort (Blob-URL shim, classic workers only).
5. No out-of-band collaborator: blind XSS/SSRF/XXE callbacks need a server.
6. Captcha solvers cost money and are a third-party dependency the operator chooses;
   nothing in this app ships a key.
7. Trust-all TLS stays a footgun: default off, red warning.

---

## 9. ACCEPTANCE CRITERIA

- With Eruda **off** (default), `get_curl` returns a POST **with its real JSON body** on a
  fetch/XHR site — the v7 regression, fixed.
- A JSON API answered through `xhr.responseType='json'` shows its response body in the
  Network tab and in `get_response_body`.
- Intercept on → a fetch call appears in the Intercept tab and the page **waits**; editing
  the body and forwarding makes the page continue with the edit; Drop fails the call.
- `todo_write` renders a checklist in the agent sheet; items flip to done as the run proceeds.
- `ask_user` ends a turn with a question and the next user message resumes the same run
  with history intact.
- Captcha: detect finds a reCAPTCHA v2 checkbox with a sitekey; with a configured key,
  `captcha_solve` returns a token and injects it; without a key it hands off via `ask_user`
  with a clear explanation instead of failing silently.
- `save_finding` + `export_bug_report` produce a Markdown report containing a sanitized
  curl under `agent/` in AxStorage.
- `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` pass with no new dependencies.

---

*Plan Version: 8.0 — AxBrowser · Status: implementing · No gating: all tools always available.*
