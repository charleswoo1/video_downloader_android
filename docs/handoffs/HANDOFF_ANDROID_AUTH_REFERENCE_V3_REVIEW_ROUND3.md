# HANDOFF — V3 Review Round 3: Anonymous-First + Reference-Faithful Meta Finalization

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Reviewed code head: `07d1d7f6b3c4bbf919984b02d9642c47e1a00869`

Status: **review correction contract / local validation only / DO NOT run CI yet / NOT merge, tag, or release authorization**

Governing contracts remain:

- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`
- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3_REVIEW_ROUND2.md`

This is the latest narrow follow-up.

Current CI policy remains manual-only:

```yaml
on:
  workflow_dispatch:
```

Do not trigger GitHub Actions until the next review explicitly says **NO BLOCKING FINDINGS / READY FOR CI**.

---

# Review result

Round 2 fixed several important items correctly:

- Instagram dummy/fabricated LSD fallback was removed.
- Instagram ruling preflight is now non-fatal for transport/non-terminal HTTP errors.
- Threads GraphQL endpoint/origin moved to `threads.com`.
- Threads `for (;;);` anti-JSON prefix is handled.
- Threads anonymous GraphQL now has a separate anonymous cookie jar and a real isolation regression test.
- Domainless Threads cookies now default to `threads.com`.
- `CONFIGURED` no longer counts as an authenticated extraction session.
- UI imports Instagram / Threads / X separately.
- X now requires `auth_token + ct0` and no longer fabricates a random ct0.
- `#HttpOnly_` Netscape support remains correct.

However, four blocking correctness issues remain before CI/device testing.

---

# BLOCKER 1 — Instagram LSD bootstrap is still not the current upstream bootstrap path

## Current upstream reference rechecked

Current `yt-dlp/yt-dlp/yt_dlp/extractor/instagram.py` performs logged-out initialization approximately as:

```text
GET https://www.instagram.com/
→ browser/webpage bootstrap
→ parse <script id="__eqmc"> JSON field "l"
   OR ["LSD",[],{"token":"..."}]
→ store real LSD
```

Then extraction performs:

```text
best-effort get_ruling_for_content
→ PolarisLoggedOutDesktopWWWPostRootContentQuery
```

## Current Android behavior

The current Android implementation obtains LSD by:

```text
GET https://www.instagram.com/p/<shortcode>/
using RequestProfile.API
→ regex ["LSD",...]
```

This is still materially different from the upstream bootstrap:

- it uses the target post page rather than the Instagram base page;
- it uses an API/CORS-style request profile instead of webpage/navigation bootstrap;
- it does not parse the current `__eqmc` LSD source.

A target post can itself be login-gated while the Instagram homepage still provides the bootstrap LSD. That can cause the new Polaris path to be skipped unnecessarily.

## Required fix

Re-read current upstream immediately before editing.

Implement a real logged-out Instagram session bootstrap equivalent to current upstream:

1. use an **anonymous** browser/navigation request to `https://www.instagram.com/`;
2. parse LSD from:
   - `<script id="__eqmc">...` field `l`, when present;
   - fallback `["LSD",[],{"token":"..."}]`;
3. cache only a real token;
4. never fabricate a token;
5. keep the ruling preflight best-effort/non-fatal;
6. use the real LSD in both form `lsd` and `X-FB-LSD`.

The target page may still be fetched later for the Relay fallback, but it should not be the only LSD bootstrap source.

## Tests

Add request/flow tests proving:

- base-page browser bootstrap occurs;
- `__eqmc.l` token is accepted;
- LSD script fallback is accepted;
- target page login wall does not prevent obtaining homepage LSD;
- no fake token is sent.

---

# BLOCKER 2 — Instagram declares LOGIN_REQUIRED before executing the current upstream Relay webpage fallback

## Current upstream reference

After Polaris GraphQL, upstream does **not** treat every missing `if_not_gated_logged_out` as immediate login-required.

Current behavior is approximately:

```text
Polaris response has no product_info
→ inspect explicit ruling errors:
   - Restricted Video → login required
   - explicit error → expected error
   - long private shortcode → login required
→ otherwise GET target webpage
→ if redirected to login → login required / anonymous access exhausted
→ parse RelayPrefetchedStreamCache
   → __bbox.result.data.xig_polaris_media
   → if_not_gated_logged_out
→ only after that fails, return final empty-media/login guidance
```

## Current Android behavior

Current `fetchPolarisLoggedOutGraphQL()` does:

```text
xig_polaris_media exists
but if_not_gated_logged_out == null
→ POLARIS_GATED_LOGGED_OUT
→ LoginRequired
```

Then `extractMediaInfo()` returns that error immediately when no authenticated session exists.

Therefore the lower HTML/Relay fallback is skipped exactly in a case where current upstream explicitly tries it.

This can create false login-required results for public posts.

## Required fix

Do not return generic `LoginRequired` merely because `if_not_gated_logged_out` is null.

Only make login-required terminal before webpage fallback when there is explicit strong evidence, e.g.:

- ruling says `Restricted Video`;
- known private/follower-only long-shortcode condition;
- target webpage actually redirects to login;
- another current reference-backed explicit auth restriction.

Otherwise continue to the target webpage / Relay fallback first.

The fallback should explicitly support the current upstream structure:

```text
RelayPrefetchedStreamCache
→ __bbox
→ result.data.xig_polaris_media
→ if_not_gated_logged_out
```

Do not rely only on broad generic deep-walk behavior.

## Tests

Add at minimum:

1. Polaris `xig_polaris_media` with null/missing `if_not_gated_logged_out`, then target webpage Relay data succeeds → extraction succeeds without login.
2. Same Polaris condition + target webpage redirects to `/accounts/login/` → `LoginRequired`.
3. Explicit `Restricted Video` remains terminal auth-required.
4. Long private shortcode remains auth-required only under the current reference condition.

---

# BLOCKER 3 — Instagram and Threads still violate the V3 anonymous-first routing contract, and Instagram “anonymous” requests can inherit active auth cookies

## Governing V3 contract

The V3 mission is explicitly:

```text
reference-aligned anonymous extractor
→ success
OR auth-required/gated/restricted
→ authenticated fallback
```

It also states:

> Anonymous mode should remain preferred for ordinary public content.

## Current code

Instagram currently starts with:

```text
if active Instagram session
→ authenticated /media/{id}/info/ first
→ then Polaris anonymous
```

Threads currently starts with:

```text
if active Threads session
→ authenticated Relay page first
→ then anonymous Barcelona GraphQL
```

This means once a user imports a valid session, ordinary public downloads unnecessarily use account cookies first.

There is an additional Instagram correctness issue:

- authenticated Instagram request syncs session cookies into the main `PlatformHttpSession.cookieJar`;
- if the authenticated API fails non-terminally and the code then executes “logged-out” Polaris/HTML requests on the same normal context, active session cookies can still be attached automatically.

So the logged-out path is not guaranteed to be logged out.

Threads already introduced a real isolated anonymous context for Barcelona; Instagram needs equivalent isolation.

## Required fix

### Instagram

Route:

```text
anonymous isolated bootstrap/ruling/Polaris/Relay
→ success: stop
→ explicit auth-required/gated/restricted AND ACTIVE session exists
   → authenticated /media/{id}/info/
→ no ACTIVE session
   → clear auth-required UI error
```

All anonymous Instagram requests must use an isolated anonymous cookie context, including:

- homepage LSD bootstrap;
- ruling preflight;
- Polaris GraphQL;
- target webpage / Relay fallback;
- any anonymous HTML profile fallback that is part of this route.

Do not destroy stored user credentials; isolate the request context.

### Threads

Route:

```text
anonymous isolated Barcelona GraphQL
→ success: stop
→ auth-required/restricted/quote/repost/current-reference fallback condition
   AND ACTIVE Threads session exists
   → authenticated Relay page
→ then only lower-priority legacy/crawler fallback as appropriate
```

Do not use authenticated Relay first for every ordinary public post merely because a session exists.

### X

Do not change the existing guest-first → auth-required → authenticated fallback ordering unless a new reference requires it.

## Tests

Add tests proving:

- ACTIVE Instagram session + public anonymous Polaris success → authenticated endpoint is never called;
- ACTIVE Threads session + public Barcelona success → authenticated Relay is never called;
- Instagram anonymous requests do not send `sessionid` even when ACTIVE credentials exist;
- Threads existing anonymous-cookie-isolation test remains;
- explicit IG/X/Threads auth-required condition with ACTIVE session invokes authenticated fallback;
- same condition without ACTIVE session returns auth-required guidance.

---

# BLOCKER 4 — Session validation is still too permissive; ACTIVE can be produced by weak substring markers

The new `CONFIGURED` extraction gate is correct, but validation itself still needs tightening.

## Threads

Current validator marks ACTIVE when **any one** of these strings appears:

```text
DTSGInitialData
"ACCOUNT_ID"
"USER_ID"
fb_dtsg
"IG_USER_EIMU"
```

Current Downstream-AV reference does not use that OR rule. It derives:

```text
fb_dtsg token
AND
credible user/account id
```

before treating the page context as authenticated.

A logged-out page can contain framework/token names or JavaScript constants without proving a logged-in user.

### Required Threads validation

Use a structured/regex-based condition aligned with the current reference:

- extract a non-empty `DTSGInitialData` / `fb_dtsg` token;
- extract a credible numeric account/user id from current authenticated page data;
- require **both** for ACTIVE.

A plain occurrence of a marker name must not be enough.

## Instagram

Current validator accepts:

```text
HTTP 200 AND
(body contains status:"ok" OR body contains the literal key "user")
```

This is also too weak for a state named ACTIVE.

Parse the JSON response structurally and require a credible authenticated user object/identifier according to the current endpoint response, not substring presence.

If response is HTTP 200 but authenticated identity cannot be proven:

```text
remain CONFIGURED
```

Do not mark EXPIRED unless the platform explicitly rejects the credentials.

## Tests

Threads:

- public page containing the text `DTSGInitialData` but no user/account id → remains CONFIGURED;
- page containing a user-id marker but no real DTSG token → remains CONFIGURED;
- real token + credible numeric user/account id → ACTIVE.

Instagram:

- `{"status":"ok"}` without authenticated user identity → remains CONFIGURED;
- `{"user":null,"status":"ok"}` → remains CONFIGURED;
- structured authenticated user with credible ID → ACTIVE;
- explicit login/checkpoint rejection → EXPIRED.

---

# BLOCKER 5 — Instagram generic Polaris/Relay parser can accept an unrelated identity-less media object

The project-level target-isolation rule is non-negotiable.

Current `findXdtMediaItem()` handling for `xig_polaris_media` effectively allows:

```kotlin
isCodeMatch = code.isBlank() || code == target
isIdMatch   = id.isBlank() || id == targetId
if (isCodeMatch || isIdMatch) return media
```

If both `code` and `id` are blank, both conditions become permissive and the parser can accept the first identity-less `xig_polaris_media` object encountered during a generic recursive walk.

That can violate strict target isolation when a page includes unrelated/recommended media structures.

## Required fix

For generic/deep-walk media discovery:

- if an object exposes `code/shortcode`, it must match target;
- if it exposes `id/pk`, it must match target numeric ID;
- if neither identity is present, **do not accept it merely because it contains media**.

The only acceptable identity-less exception is a narrowly recognized structural provenance that current upstream proves is the target post itself, e.g. the exact target-page:

```text
RelayPrefetchedStreamCache
→ __bbox.result.data.xig_polaris_media
```

If using such an exception, implement it as a dedicated parser path rather than weakening the generic recursive matcher.

## Tests

- unrelated `xig_polaris_media` with different code/id → reject;
- identity-less `xig_polaris_media` in arbitrary/unrelated container → reject;
- exact target RelayPrefetchedStreamCache structural path → accept when current reference establishes target provenance;
- existing XDT target-isolation regressions remain green.

---

# Resolved Round 2 findings

Do not regress these:

- no dummy Instagram LSD;
- non-fatal ruling preflight;
- Threads `threads.com` endpoint/origin;
- anti-JSON prefix handling;
- isolated anonymous Threads cookie jar;
- Threads default domain = `threads.com`;
- CONFIGURED is not extraction-authenticated;
- platform-specific UI import;
- X requires `auth_token + ct0`;
- no random/fabricated X ct0;
- HttpOnly Netscape support.

---

# Non-blocking before merge/release

Third-party reference attribution still needs to be finalized before merge/release if code was adapted from MIT-licensed sources.

No need to trigger CI for that during this review-fix round.

---

# Required local verification

After all five blockers are fixed:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Then push to the same PR branch and stop.

Do **not** run GitHub Actions.

Return:

```text
Head commit:

Reference recheck:
- Instagram current yt-dlp:
- Threads current Downstream-AV:
- X references (only if changed):

Blocker 1 Instagram bootstrap:
- base-page bootstrap:
- __eqmc / LSD handling:
- tests:

Blocker 2 Instagram Relay fallback:
- terminal auth conditions:
- RelayPrefetchedStreamCache path:
- tests:

Blocker 3 anonymous-first:
- Instagram routing:
- Instagram cookie isolation:
- Threads routing:
- tests:

Blocker 4 validation:
- Threads structured auth proof:
- Instagram structured auth proof:
- tests:

Blocker 5 IG target isolation:
- generic matcher:
- dedicated Relay provenance:
- tests:

Local unit tests:
Local lint:
Local assembleDebug:

Review gate: READY FOR REVIEW

Known limitations:
Merged: NO
Release created: NO
CI triggered: NO
```

Only after the next ChatGPT review says **NO BLOCKING FINDINGS / READY FOR CI** may `Android CI` be manually dispatched.
