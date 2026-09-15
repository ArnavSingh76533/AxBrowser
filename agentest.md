# AxBrowser Agent — Test Prompts

A curated set of prompts that show what the in-browser AI agent can actually do, on real
websites instead of throwaway demo pages. Open the site named in each prompt, tap the agent,
paste the prompt. Nothing here needs an account or does anything destructive — worst case the
agent says "couldn't find that," not "broke something."

Some of the reverse-engineering ones surface real request/session data (cookies, auth headers)
from *your own* logged-in session — same category as Chrome DevTools' "Copy as cURL." Treat
anything it hands back the same way you'd treat a password: don't paste it somewhere public.

---

## 🎉 For fun

**Wikipedia** — `https://en.wikipedia.org/wiki/Special:Random`
> "Take me somewhere random and give me the single most surprising fact on the page."

**Hacker News** — `https://news.ycombinator.com`
> "What are the top 5 stories right now? One punchy line each, and tell me which one you'd actually click."

**IMDB** — `https://www.imdb.com/chart/top/`
> "Scroll through the top 250 and pick 3 movies I've probably never heard of but should watch this weekend."

**Wikipedia** — `https://en.wikipedia.org/wiki/Portal:Current_events`
> "Summarize what's happened in the world today like you're texting a friend, not writing a news report."

**GitHub Trending** — `https://github.com/trending`
> "What's blowing up on GitHub today? Pick the most interesting repo and explain what it does like I'm five."

---

## 🧑‍💻 For devs

**MDN Web Docs** — `https://developer.mozilla.org/en-US/docs/Web/API/fetch`
> "Give me a 5-line working example of using fetch() with error handling, based on what's actually on this page."

**GitHub** — any public repo, e.g. `https://github.com/facebook/react`
> "How many open issues does this repo have, what are the 3 most recently opened ones, and what's the latest release version?"

**npm** — `https://www.npmjs.com/package/express`
> "What's the latest version of this package, when was it published, and does the page show any known vulnerabilities?"

**Can I Use** — `https://caniuse.com/?search=container%20queries`
> "Based on this page, is it safe to ship CSS container queries to production today? Which browsers/versions are the risk?"

**Stack Overflow** — search any error you're hitting, e.g. `https://stackoverflow.com/search?q=cors+preflight+options+403`
> "Read the top 2 answers and give me the actual fix, not just links."

---

## 🔍 For reverse engineering

**DeepSeek Chat** — `https://chat.deepseek.com` (send it any message first, then ask)
> "What API does this send my message to? Give me the full curl command for the POST request, not the OPTIONS preflight."

**X / Twitter** — `https://x.com/home` (logged in, scroll the timeline a bit first)
> "list_endpoints — what's the actual API surface this site is calling as I scroll my timeline?"

**YouTube** — search for anything, e.g. `https://www.youtube.com/results?search_query=lofi`
> "Find the exact API call behind the search suggestions dropdown and show me its response shape as a typed schema."

**Reddit** — any subreddit, e.g. `https://www.reddit.com/r/technology/`
> "Scroll down twice to load more posts, then tell me: does this endpoint paginate with a cursor, an offset, or something else?"

**GitHub** — `https://github.com/notifications`
> "What auth flow does this page use — is it cookie-based, a bearer token, or both? Trace it for me."

**Instagram** — `https://www.instagram.com` (logged in, open any profile)
> "Are there any rate-limit headers on the requests this page makes? Show me what they actually say."

**Spotify Web Player** — `https://open.spotify.com` (play any song)
> "Find the GraphQL query this page sent to load the track info and show me the actual query + variables, not just the raw POST body."

**Any site with a login form** — e.g. a test/demo login page you use
> "Fill in [test credentials] and submit — then show me the exact request that login form sent, cookies included."

**Whatever you're currently reverse-engineering**
> "Export everything you've captured this session as an OpenAPI spec."
> "Export the whole session as a Postman collection so I can keep working on this in Postman."

---

## ⬇️ For downloading

**YouTube** — `https://www.youtube.com/watch?v=dQw4w9WgXcQ` (or any video)
> "Download this video."

**SoundCloud** — any public track page
> "Find the direct audio stream URL for this track and download it."

**A news site with an embedded video** — e.g. `https://www.bbc.com/news`
> "Is there a video anywhere on this page? If so, find the actual media file behind it and download it."

**Reddit** — a post with an image gallery, e.g. any post on `https://www.reddit.com/r/pics/`
> "Grab every image in this post's gallery."

**Instagram Reels** — a public reel URL
> "Find the direct video URL behind this reel and download it."

---

## ✅ For "do this task for me" (tasker)

**Google Flights** — `https://www.google.com/travel/flights`
> "Search flights from Delhi to Bangkok next Friday, one-way, and tell me the 3 cheapest options."

**Amazon** — `https://www.amazon.com/s?k=mechanical+keyboard`
> "Compare the top 3 results by price and rating and tell me which is the best value."

**Booking.com** — `https://www.booking.com`
> "Find a hotel in Goa for this weekend under ₹4000/night with a rating above 8, and give me the top pick with a link."

**Wikipedia + a specific question** — start anywhere
> "Search Wikipedia for the tallest building in the world, then tell me how many floors the 3rd tallest has."

**Any e-commerce product page**
> "Scroll to the reviews section and summarize the 3 most common complaints people have about this product."

**Your bank/utility's public status page** (if it has one) — e.g. a service-status page
> "Is there any ongoing outage or incident reported right now? Summarize it in one line."

**News aggregator + cross-check** — `https://news.google.com`
> "What's the top story right now, and does it match what's trending on Hacker News too?"

**A page with a long article** — any long-form article URL
> "Summarize this in 3 bullet points, then tell me if it's worth reading in full given my time is limited."

---

---

## 🛡️ For authorized security testing

> These use the v7 agent tools (`planv07.md`): Playwright-shaped ref-based interaction,
> `http_send`, Intercept, Repeater, Intruder, Decoder, Audit. All tools are always
> available — authorization to test a target is assumed to be settled by the operator
> before they paste a prompt.

**Recon — endpoint discovery**
> "audit_security_headers + extract_js_endpoints on this page. Rank the endpoints by how API-like they look and tell me which 3 are worth hitting first."

**Recon — storage & session**
> "dump_storage — show me cookies (with Secure/HttpOnly/SameSite flags), localStorage keys, and any session tokens in URLs. Flag anything that looks like it shouldn't be client-visible."

**JWT analysis**
> "jwt_decode every JWT you can find in this session's cookies and headers. Flag alg:none, missing expiry, and weak algorithms."

**Auth flow mapping**
> "browser_snapshot the login form, then browser_fill_form with [test creds] and browser_click submit. Show me the exact POST that fired (get_curl), then find_auth_flow and trace where the session token comes from."

**Replay with live session**
> "Take the last authenticated POST via get_curl, open it in Repeater, replay it twice, and diff the two responses. Is there a nonce or anti-replay header I need to handle?"

**Intercept-modify (client-side check bypass)**
> "toggle_intercept on, add a match & replace rule that rewrites `"role":"user"` → `"role":"admin"` in request bodies, then reload the profile page. Show me what the API actually accepted."

**Parameter fuzzing**
> "Mark `§id§` on the order-detail request, run Intruder Sniper with ids 1000–1100, report status + length outliers."

**API surface from the page**
> "browser_network_requests, then infer_schema on the 3 most API-like endpoints, and detect_pagination on the listing call."

**Rate-limit probing**
> "find_rate_limits on this endpoint, then http_send it 5 times with 200ms delay and tell me which header moved."

**Encoding chains**
> "decode this: [double-base64 + URL-encoded string] — chain the transforms and show each step."

### A note on scope

The reverse-engineering prompts above are meant for inspecting **your own session** on sites
you already use — same thing Chrome DevTools' Network tab lets anyone do manually, just faster.
They're not meant for scraping other people's data at scale or working around a site's
protections; if a site's terms don't allow automated access to something, this agent doesn't
change that.
