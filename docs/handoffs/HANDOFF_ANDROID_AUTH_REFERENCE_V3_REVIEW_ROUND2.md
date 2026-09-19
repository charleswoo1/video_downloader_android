# HANDOFF — V3 Review Round 2: Real Request/Auth Correctness

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Reviewed head: `0c4ce1c50b59a418e61a4f4f0968b7e5c31bc466`

Status: **review correction contract / local validation only / DO NOT run CI yet / NOT merge, tag, or release authorization**

Governing contracts:

- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`
- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3_REVIEW_FIXES.md`

This handoff is the latest narrow follow-up and supersedes the previous review-fix handoff where the findings below are more specific.

The current CI policy remains manual-only:

```yaml
on:
  workflow_dispatch:
```

Do not trigger GitHub Actions while any blocker below remains.

---

# Review summary

The previous round made meaningful progress:

- Instagram now uses numeric `media_id` variables and request-capture tests.
- Threads now bootstraps an LSD token before GraphQL and has request-capture tests.
- `#HttpOnly_` Netscape cookie records are now parsed correctly.
- `CONFIGURED` status was added and the UI distinguishes “待驗證” from “已連線”.
- X adult-content authenticated fallback remains directionally correct.

However, comparison against the **current GitHub references again** found remaining real-request/auth correctness blockers. The new tests still allow several request paths that differ materially from the reference implementations.

Do not manually trigger CI or ask for owner-device acceptance yet.

---

# BLOCKER 1 — Instagram Polaris still sends a fabricated LSD token and makes the ruling preflight too fatal

## Current reference rechecked

Current upstream:

`yt-dlp/yt-dlp/yt_dlp/extractor/instagram.py`

Relevant behavior:

- initializes/obtains a real LSD token;
- performs `web/get_ruling_for_content` as a **non-fatal** accessibility check;
- uses numeric `media_id`;
- sends the real LSD in both form `lsd` and `X-FB-LSD`;
- only sends `X-CSRFToken` when a valid CSRF token was actually granted;
- if GraphQL does not provide product data, continues into the current webpage / Relay-prefetched fallback.

## Current Android issue

`NativeInstagramEngine.fetchPolarisLoggedOutGraphQL()` currently contains:

```kotlin
val effectiveLsd = lsdToken ?: "AVq_dummy_lsd"
```

This directly violates the prior handoff rule:

> Do not add hard-coded placeholder CSRF/LSD values.

A fabricated LSD token can make the outgoing request look structurally correct in a unit test while being rejected by Instagram.

The implementation also treats the content-ruling request as mandatory/fatal:

- transport failure => immediate failure;
- non-2xx => immediate `ApiError`.

Current yt-dlp uses the ruling check with `fatal=False`; failure of that advisory/preflight request must not automatically prevent the later reference path from being attempted.

## Required fix

1. Remove all dummy/fabricated LSD fallback behavior.
2. Obtain a real LSD token from the same current bootstrap sources used by the reference.
3. If a real LSD cannot be obtained:
   - do not send a fake GraphQL request;
   - return a fallback-eligible technical/platform-changed result and continue to the existing HTML/Relay fallback.
4. Make the ruling request best-effort/non-fatal unless it returns an explicit terminal content/auth restriction that is independently trustworthy.
5. Preserve:
   - numeric `media_id`;
   - `PolarisLoggedOutDesktopWWWPostRootContentQuery`;
   - real `X-FB-LSD`;
   - real form `lsd`;
   - optional real `X-CSRFToken`;
   - strict target isolation.

## Required tests

Add request/flow tests for:

- missing LSD => GraphQL is **not** sent with a placeholder;
- ruling transport failure + valid real bootstrap LSD => later Polaris request can still be attempted;
- ruling non-terminal 5xx/temporary failure does not become a false terminal content error;
- no test may accept `AVq_dummy_lsd` or any fixed fake token in production logic.

---

# BLOCKER 2 — Threads Barcelona path still differs materially from the current reference

## Current reference rechecked

Current reference:

`boneless3vil/Downstream-AV/yt_dlp_plugins/extractor/threads.py`

The current file uses:

```text
https://www.threads.com/api/graphql
Origin: https://www.threads.com
Referer: target threads.com URL
X-IG-App-ID: 238260118697367
X-FB-LSD: <real page LSD>
Sec-Fetch-Site: same-origin
Sec-Fetch-Mode: cors
Sec-Fetch-Dest: empty
```

It also explicitly strips the anti-JSON-hijacking prefix:

```text
for (;;);
```

before JSON parsing.

Most importantly, it documents a current authenticated-session edge case:

> session cookies + anonymous form is rejected; when falling back anonymously, retry with an **empty cookie jar**.

## Current Android issues

### A. Host/origin mismatch

The app normalizes target URLs to `threads.com`, but `fetchBarcelonaGraphQL()` still uses:

```text
endpoint = https://www.threads.net/api/graphql
Origin   = https://www.threads.net
```

while the current reference uses `threads.com`.

This can introduce redirects/cross-origin behavior and is not reference-faithful.

### B. Anti-JSON prefix is not handled

Current code does:

```kotlin
JSONObject(resp.body)
```

without removing `for (;;);`.

A valid real Threads API response with the prefix will fail JSON parsing even though all mocked JSON fixtures pass.

### C. “Anonymous” GraphQL can still carry authenticated cookies

`PlatformHttpSession.fetch()` automatically syncs platform credentials when a session is available.

Therefore after an authenticated Relay attempt fails, `fetchBarcelonaGraphQL()` can issue the nominally anonymous GraphQL request with the same authenticated Threads cookies still in the shared cookie jar.

That is exactly the request combination the current reference says is rejected.

### D. Raw Threads Cookie imports default to the wrong host

`PlatformCookieParser.getDefaultDomain(Platform.THREADS)` currently returns:

```text
threads.net
```

but the engine normalizes authenticated target pages to:

```text
threads.com
```

For raw `sessionid=...` input with no explicit domain, the cookie can therefore be stored for `threads.net` and never be sent to the `threads.com` authenticated Relay page.

## Required fix

1. Re-align the active Barcelona request contract to current `threads.com` reference behavior.
2. Use one coherent host for:
   - bootstrap;
   - GraphQL endpoint;
   - Origin;
   - Referer;
   - default raw-cookie domain.
3. Keep `threads.net` accepted for imported legacy/domain-explicit exports if useful, but do not default domainless input to a host the active engine does not use.
4. Strip only the documented `for (;;);` anti-JSON prefix before `JSONObject` parsing.
5. Introduce a genuinely anonymous Barcelona request context:
   - no imported authenticated Threads session cookies;
   - a separate/isolated cookie jar/session for the anonymous bootstrap + GraphQL pair;
   - retain only the anonymous bootstrap cookies/tokens required by that request.
6. Do not clear or destroy the user's authenticated stored session; isolate the anonymous request path instead.
7. Preserve strict target identity:
   - `post.code == target shortcode`, or
   - `post.pk == target numeric pk`.

## Required tests

Add tests proving:

- endpoint is current `threads.com/api/graphql`;
- Origin/Referer are coherent `threads.com`;
- raw domainless Threads cookie defaults to `threads.com`;
- response `for (;;);{"data":...}` parses successfully;
- with a configured/active Threads session, anonymous Barcelona fallback does **not** send `sessionid`/authenticated cookies;
- anonymous bootstrap LSD/csrftoken can still flow into the isolated GraphQL request;
- wrong-target/unrelated video remains rejected.

---

# BLOCKER 3 — Session state semantics still allow unvalidated credentials to drive authenticated extraction

## Current issue A — CONFIGURED counts as authenticated

Current provider behavior:

```kotlin
cookiesFor(...)
  → returns cookies for ACTIVE **or CONFIGURED**

hasAuthenticatedSession(...)
  → true for ACTIVE **or CONFIGURED**
```

This means a session displayed to the user as:

```text
待驗證
```

is already considered an authenticated session by Instagram / Threads / X engine routing.

That defeats the purpose of adding `CONFIGURED`.

## Required behavior

Use:

```text
CONFIGURED
→ stored and available to the validator
→ NOT eligible for authenticated extraction

ACTIVE
→ validated
→ eligible for authenticated extraction
```

`validateSession()` already reads from `credentialStore.getCookies()` directly, so there is no need for public extraction-facing `cookiesFor()` / `hasAuthenticatedSession()` to expose CONFIGURED credentials.

---

## Current issue B — Threads validation has an anonymous-200 false positive

Current Threads validator approximately does:

```text
GET https://www.threads.net/
HTTP 200
→ ACTIVE
```

But the public Threads home page can return HTTP 200 while logged out.

Therefore a bad/unused session cookie can be marked “已連線” simply because a public page loaded.

## Required fix

Use a current reference-supported authenticated signal rather than HTTP 200 alone.

For example, if still current after reference recheck, validate the logged-in page context by requiring authenticated-only markers such as:

- `DTSGInitialData` / `fb_dtsg`;
- a credible logged-in account/user ID marker (`ACCOUNT_ID`, `USER_ID`, `IG_USER_EIMU`);
- or another current auth-only endpoint/response.

Do not invent a marker; verify it against current GitHub/reference behavior first.

A plain public 200 must keep the session CONFIGURED/unverified, not ACTIVE.

## Required tests

- CONFIGURED => `hasAuthenticatedSession == false`;
- CONFIGURED => extraction-facing `cookiesFor == empty`;
- validator can still access and validate stored cookies internally;
- Threads public anonymous 200 => remains CONFIGURED, not ACTIVE;
- authenticated-only marker(s) => ACTIVE;
- explicit rejection => EXPIRED;
- transient network failure => preserves CONFIGURED/ACTIVE as appropriate without false expiry.

---

# BLOCKER 4 — Meta import still clones one domainless cookie string across Instagram and Threads and is not atomic

## Current UI/provider behavior

The UI still does:

```text
if target is Instagram OR Threads
→ importMetaSession(rawInput)
```

and `importMetaSession()` sequentially does:

```text
importSession(INSTAGRAM, rawInput)
then
importSession(THREADS, rawInput)
```

For a domainless raw header such as:

```text
sessionid=...
```

the same credential value is fabricated into two different platform domains.

Current GitHub references do **not** establish that Instagram's `sessionid` and Threads' `sessionid` values are interchangeable.

This also creates a partial-write bug:

```text
Instagram import succeeds
Threads import fails
→ overall Result is failure
→ Instagram credentials remain stored
```

The UI then validates only the selected target platform.

## Required fix

Prefer platform-specific import:

```text
Instagram card → import Instagram cookies only
Threads card   → import Threads cookies only
X card         → import X cookies only
```

If keeping a “Meta combined import” convenience path, allow it only for a domain-explicit Netscape/JSON export that actually contains independently valid Instagram and Threads cookie records. Do not clone a domainless raw header into both domains.

Combined import must also be atomic or explicitly return independent per-platform results without pretending all-or-nothing success.

## Required tests

- Instagram-only Netscape export can be imported without requiring Threads cookies;
- Threads-only Netscape export can be imported without requiring Instagram cookies;
- domainless Instagram input is not silently copied to Threads;
- domainless Threads input is not silently copied to Instagram;
- failed second-platform combined import cannot leave an undocumented partial write;
- UI validates the platform actually imported.

---

# BLOCKER 5 — X authenticated session still permits missing ct0 and fabricates a random CSRF token

## Current reference basis

The previously researched current X references (`yt-dlp`, `medialab/minet`, `TheFunny/TelegramTwitterMediaBot`) use an authenticated browser session with at least:

```text
auth_token
ct0
x-csrf-token = ct0
x-twitter-auth-type: OAuth2Session
```

## Current Android issue

`importSession(Platform.X)` only requires `auth_token`.

Then `NativeXEngine.fetchPostViaAuthenticatedGraphQL()` does:

```text
ct0 missing
→ try cookie jar
→ still missing
→ generate random ct0
```

A random CSRF value is not evidence of a valid X browser session and can turn a malformed import into a confusing authentication failure.

## Required fix

1. Require both `auth_token` and `ct0` for an X session import to become CONFIGURED.
2. Remove random/fabricated ct0 fallback from authenticated GraphQL.
3. Authenticated request must use the imported/session-issued ct0 and send the identical value in:
   - Cookie `ct0=...`;
   - `x-csrf-token`.
4. Missing ct0 should remain a configuration/auth error, not generate a new token.

## Required tests

- auth_token without ct0 => import/configuration failure;
- auth_token + ct0 => CONFIGURED;
- authenticated GraphQL never fabricates ct0;
- `Cookie ct0` exactly equals `x-csrf-token`.

---

# Findings resolved from previous review

The following previous finding is considered fixed in this round:

## Netscape `#HttpOnly_` records

The parser now:

- distinguishes `#HttpOnly_` records from comments;
- strips the marker;
- preserves the cookie;
- sets OkHttp `httpOnly`;
- keeps platform-domain isolation.

Keep the new regression tests.

The addition of `CONFIGURED` and its UI badge is also directionally correct, but Blocker 3 must be fixed before its state semantics are complete.

---

# Non-blocking / before merge-release

## Third-party notices

The Phase A research notes MIT-derived reference implementations. Before merge/release, ensure required attribution is present in the repository's third-party notices/license documentation.

This does not need to trigger CI now.

---

# Required local verification

After fixing all blockers:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Then push to the same branch and stop.

Do **not** run GitHub Actions yet.

Return:

```text
Head commit:

Reference recheck:
- Instagram yt-dlp:
- Threads Downstream-AV:
- X auth references:

Blocker 1 Instagram:
- real LSD behavior:
- ruling non-fatal behavior:
- tests:

Blocker 2 Threads:
- host/endpoint:
- anti-JSON prefix:
- anonymous cookie isolation:
- default cookie domain:
- tests:

Blocker 3 session gate:
- CONFIGURED vs ACTIVE:
- Threads auth validation:
- tests:

Blocker 4 Meta import:
- platform-specific import behavior:
- atomic/independent result behavior:
- tests:

Blocker 5 X ct0:
- import requirements:
- no-fabrication behavior:
- tests:

Local unit tests:
Local lint:
Local assembleDebug:

Review gate: BLOCKERS REMAIN / READY FOR REVIEW

Known limitations:
Merged: NO
Release created: NO
CI triggered: NO
```

Only after the next ChatGPT review reports **no blocking findings / READY FOR CI** may Android CI be manually dispatched.
