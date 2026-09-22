# HANDOFF — Android WebView Login V1 Phase 3: X

Repository: `charleswoo1/video_downloader_android`  
Branch: `feat/android-webview-login-v1-x`  
Baseline main: `1dd424ef20861f37135863e43fe73d57ed209de3`

## Objective

Implement **X WebView Login V1** by reusing the existing shared WebView-login/session infrastructure already proven by Instagram and Threads.

This phase is intentionally narrow:

- add direct on-device X login
- capture and persist the minimum authenticated X cookies
- validate the session and transition it to ACTIVE when identity/session proof succeeds
- expose the same "登入" action for X in Platform Sessions
- preserve existing manual cookie import
- preserve all current X extraction behavior

Do **not** redesign or rewrite `NativeXEngine` in this phase.

The purpose of Phase 3 is to answer one question cleanly:

> After the app can obtain a real authenticated X web session on-device, how much of the existing X failure rate disappears without changing the extractor?

If owner-device testing still shows major failures with an ACTIVE X session, extractor/schema work becomes a separate Phase 4.

---

## Current baseline

Already implemented on main:

- shared `PlatformWebLoginScreen`
- shared `PlatformWebLoginCoordinator`
- `WebViewCookieCapture`
- encrypted/session storage through `AuthenticatedPlatformSessionProvider`
- session states: NOT_CONFIGURED / CONFIGURED / ACTIVE / EXPIRED
- manual X cookie import
- X mandatory-cookie validation already requires:
  - `auth_token`
  - `ct0`
- `WebViewCookieCapture.isCandidateOrigin()` already recognizes:
  - `x.com`
  - `*.x.com`
  - `twitter.com`
  - `*.twitter.com`
- `NativeXEngine` already has an authenticated GraphQL fallback path using stored X session cookies:
  - `GRAPHQL_AUTHENTICATED`

Current X validator in `AuthenticatedPlatformSessionProvider.validateSession(Platform.X)` calls:

`https://api.x.com/1.1/account/settings.json`

with:

- bearer token
- `x-csrf-token: <ct0>`
- `x-twitter-auth-type: OAuth2Session`
- `Cookie: auth_token=...; ct0=...`

Do not proactively replace this validator. Validate it on owner device first. If it proves incompatible with current X web sessions, fix the validator narrowly in a follow-up review round.

---

## Reference contract

Use current browser behavior and public reference implementations only to confirm the login/session contract.

Expected login URL:

`https://x.com/i/flow/login`

Minimum required cookies:

- `auth_token`
- `ct0`

Optional allowlisted cookies:

- `twid`
- `kdt`

Do not make `twid` or `kdt` required for V1.

Do not persist unrelated advertising/tracking cookies merely because they are present, including examples such as:

- `guest_id`
- `guest_id_ads`
- `guest_id_marketing`
- `personalization_id`

unless later owner-device evidence proves one is actually required.

---

## Required implementation

### 1. Add X to PlatformWebLoginCatalog

Add `PlatformWebLoginCatalog.X`.

Recommended contract:

```kotlin
val X = PlatformWebLoginConfig(
    platform = Platform.X,
    loginUrl = "https://x.com/i/flow/login",
    cookieProbeUrls = listOf("https://x.com/"),
    requiredCookieNames = setOf("auth_token", "ct0"),
    optionalCookieNames = setOf("twid", "kdt"),
    allowThirdPartyCookies = true,
    intermediatePatterns = listOf(
        "/i/flow/login",
        "/i/flow/",
        "/login",
        "/account/access",
        "/account/login_verification"
    ),
    postLoginProbe = null
)
```

Then:

```kotlin
configFor(Platform.X) -> X
```

Do not change Instagram or Threads configs except when a shared test must be updated for the new supported platform.

### 2. Add X WebView Login action in UI

The X card in `PlatformSessionsDialog` currently has import / validate / clear but no WebView-login action.

Add:

```kotlin
onWebLogin = { onStartWebLogin(Platform.X) }
```

Use the same shared screen/coordinator; do not create a separate X-specific WebView screen unless the shared implementation is proven incapable.

### 3. Cookie capture

A candidate X session is valid for import only when both required cookies are present:

- `auth_token`
- `ct0`

The captured/persisted header must contain only the X allowlist:

- required: `auth_token`, `ct0`
- optional: `twid`, `kdt`

Do not persist arbitrary cookies.

Do not log cookie values.

### 4. Origin handling

Keep strict origin semantics already provided by `WebViewCookieCapture.isCandidateOrigin()`.

Allowed:

- x.com
- subdomains of x.com
- twitter.com
- subdomains of twitter.com

Rejected:

- evilx.com
- x.com.evil.example
- twitter.com.evil.example
- unrelated hosts

Do not use substring-only host validation.

### 5. Intermediate login / challenge flow

Intermediate URLs are not success signals.

Examples such as:

- `/i/flow/login`
- `/i/flow/*`
- `/account/access`
- login verification / challenge steps

must remain inside the WebView so the user can complete the flow.

Do not mark login successful merely because the WebView navigated away from the login URL.

Actual success requires:

1. required cookie candidate exists
2. captured session imports successfully
3. `validateSession(Platform.X)` returns resulting state ACTIVE

If the validator returns CONFIGURED, keep the session as CONFIGURED and do not falsely claim ACTIVE.

If it returns EXPIRED, show retry/failure state.

### 6. Cookie probe strategy — do not over-engineer

Start with:

`cookieProbeUrls = ["https://x.com/"]`

Do not change shared coordinator behavior to merge multiple probe URLs unless implementation/testing demonstrates a real need.

If tests or owner-device evidence later show that `auth_token` and `ct0` are split between x.com and twitter.com CookieManager probes, stop and report that finding before redesigning shared probe aggregation.

Do not blindly concatenate cookies from multiple domains.

### 7. Existing X validator

Keep the existing X validator initially.

Acceptance target:

```
capture auth_token + ct0
-> importCapturedSession(X)
-> CONFIGURED
-> validateSession(X)
-> ACTIVE
```

If the validator returns CONFIGURED/EXPIRED even though the user is visibly signed in and the required cookies were captured, do not patch `NativeXEngine`.

Instead, report exact sanitized validation diagnostics and stop for ChatGPT review.

A narrow validator change can then be designed separately.

### 8. Preserve existing extractor behavior

Do not change:

- X guest-token bootstrap
- bearer-token extraction
- GraphQL query IDs
- GraphQL feature flags
- transaction ID generation
- tombstone classification
- NSFW/age-restricted classification
- provisional unavailable behavior
- guest refresh
- HTML fallback
- `GRAPHQL_AUTHENTICATED` routing
- yt-dlp fallback
- media parsing
- download logic

Phase 3 is authentication plumbing only.

---

## Security constraints

Never:

- intercept username/password fields
- inject JavaScript to read credentials
- log `auth_token`
- log `ct0`
- expose raw cookie headers in diagnostics
- sync X cookies into Instagram or Threads
- read cookies from the installed native X app
- implement OAuth or token exchange in this phase

The user types credentials directly into X's own WebView page.

Cookie values remain app-private and encrypted using the existing credential store.

---

## Required tests

Add focused unit/regression coverage.

### Catalog

1. `configFor(Platform.X)` exists.
2. login URL == `https://x.com/i/flow/login`.
3. required cookies == `auth_token + ct0`.
4. optional allowlist includes `twid + kdt`.
5. IG and Threads configs remain unchanged.

### Origin validation

6. `https://x.com/` -> allowed.
7. `https://mobile.x.com/` -> allowed.
8. `https://twitter.com/` -> allowed.
9. `https://mobile.twitter.com/` -> allowed.
10. `https://evilx.com/` -> rejected.
11. `https://x.com.evil.example/` -> rejected.
12. `https://twitter.com.evil.example/` -> rejected.

### Cookie candidate filtering

13. auth_token only -> MissingRequired(ct0).
14. ct0 only -> MissingRequired(auth_token).
15. auth_token + ct0 -> Candidate.
16. auth_token + ct0 + twid + kdt -> Candidate preserving allowlisted values.
17. auth_token + ct0 + tracking cookies -> Candidate excluding tracking cookies.
18. duplicates -> latest occurrence wins according to existing shared behavior.
19. candidate `toString()` must not reveal values.

### Coordinator / state flow

20. X candidate imports through `importCapturedSession(X)`.
21. after successful validation ACTIVE is surfaced as WebView login success.
22. CONFIGURED is not falsely reported as ACTIVE.
23. EXPIRED produces retry/failure state.
24. challenge/intermediate URL does not itself complete login.
25. leaving candidate origin does not capture cookies.

### Persistence/regression

26. existing manual X cookie import remains working.
27. Instagram WebView login regression suite remains green.
28. Threads WebView login regression suite remains green.
29. no change to X native extractor tests except compile compatibility if necessary.

---

## Local validation

Run:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

All must PASS locally.

Do not trigger GitHub Actions yet.

---

## Workflow / Git discipline

Work only on:

`feat/android-webview-login-v1-x`

Do not commit directly to main.

After implementation:

1. commit
2. push to the same feature branch
3. stop
4. wait for ChatGPT review

Do not open PR unless explicitly instructed.

Do not run GitHub Actions until ChatGPT review authorizes the manual CI gate.

Do not merge.

Do not tag.

Do not release.

---

## Owner-device acceptance gate after review + CI

After code review and manual CI PASS, owner-device testing should confirm:

1. Platform Sessions shows an X "登入" action.
2. WebView opens X login flow.
3. normal sign-in can complete.
4. 2FA/challenge can continue if prompted.
5. `auth_token` is captured.
6. `ct0` is captured.
7. session validation reaches ACTIVE / 已連線.
8. app restart retains usable session state.
9. manual X cookie import still works.
10. multiple X posts are retested, including:
   - one previously anonymous-successful video
   - at least three previously failing X videos
   - age/login-restricted sample if available

For each failed/successful owner-device extraction, preserve sanitized EngineTrace/profile sequence.

Expected evidence for authenticated recovery, when applicable:

```
GRAPHQL
-> auth-gated evidence
-> GRAPHQL_AUTHENTICATED
-> SUCCESS
```

Do not force `GRAPHQL_AUTHENTICATED` for generic technical failures that are not auth-gated.

---

## Stop conditions

Stop and request review if any of the following is discovered:

- X login succeeds visually but required cookies cannot be obtained from x.com probe.
- cookies appear split between x.com and twitter.com and shared multi-probe behavior would need redesign.
- required cookies are present but existing X validator cannot reach ACTIVE.
- implementing X WebView login appears to require changing `NativeXEngine`.
- a shared IG/Threads WebView regression appears.

Do not improvise around these boundaries.

---

## Required completion report

Return exactly this structure:

```
X WEBVIEW LOGIN V1 — PHASE 3

Branch:
Implementation head:

Config:
- login URL:
- required cookies:
- optional cookies:
- candidate origins:

UI:
- X WebView login action:
- shared screen/coordinator reused:

Cookie capture:
- auth_token:
- ct0:
- allowlist filtering:
- secret logging:

Validation:
- existing X validator changed: YES/NO
- expected state flow:
- any validator concern:

Extractor:
- NativeXEngine changed: YES/NO
- GraphQL behavior changed: YES/NO

Tests added:
- catalog:
- origins:
- required-cookie cases:
- tracking-cookie exclusion:
- coordinator/state:
- manual import regression:
- IG regression:
- Threads regression:

Local unit tests:
Local lint:
Local assembleDebug:

GitHub CI triggered: NO
PR opened: NO
Merged: NO
Release created: NO

Known limitations:

Ready for ChatGPT review: YES
```
