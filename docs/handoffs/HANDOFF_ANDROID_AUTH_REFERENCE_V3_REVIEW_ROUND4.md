# HANDOFF — V3 Review Round 4: Final Auth-Gating / Relay Provenance Corrections

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Reviewed code head: `9c714940a02860599fdeaf1e5c2c8b0077e8b345`

Status: **narrow review correction / local validation only / DO NOT run CI yet / NOT merge, tag, or release authorization**

Governing contracts remain:

- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`
- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3_REVIEW_ROUND3.md`

Current CI policy remains manual-only:

```yaml
on:
  workflow_dispatch:
```

Do not manually trigger GitHub Actions until the next review explicitly says **NO BLOCKING FINDINGS / READY FOR CI**.

---

# Review summary

Round 3 fixed the major architectural issues correctly:

- Instagram now bootstraps LSD from the anonymous Instagram base page.
- `__eqmc.l` and LSD-token fallback parsing were added.
- dummy/fabricated LSD remains removed.
- Instagram ruling preflight remains non-fatal for technical failures.
- Instagram no longer treats missing `if_not_gated_logged_out` as immediate generic login-required.
- Instagram and Threads now start with anonymous extraction before authenticated fallback.
- Instagram anonymous requests use the isolated anonymous cookie context.
- Threads Barcelona anonymous isolation remains intact.
- session validation is significantly stricter.
- generic `xig_polaris_media` matching now requires target identity.
- X auth behavior from prior review remains intact.

However, four narrow correctness blockers remain.

---

# BLOCKER 1 — Authenticated fallback eligibility is still too broad

The V3 contract is not merely “anonymous request happens first”. It requires:

```text
anonymous success
OR
explicit auth-required / gated / restricted supported case
    → authenticated fallback
```

An unrelated parser/network/platform-change failure must not cause the app to send the user's account session merely because an ACTIVE session exists.

## Instagram current issue

Current routing eventually does:

```text
if anonymous extraction failed
AND ACTIVE Instagram session exists
→ API_AUTHENTICATED
```

for **any** remaining anonymous failure.

The code already calculates:

```text
polarisGatedOrAuthRequired
htmlLoginRedirected
```

but `polarisGatedOrAuthRequired` is not actually used to gate the authenticated fallback.

Therefore cases such as:

- homepage/bootstrap parse regression;
- parser/schema drift;
- network/5xx failure;
- unrelated technical HTML failure;

can still cause account cookies to be used.

## Threads current issue

Current routing does:

```text
Barcelona GraphQL failed for almost any non-RateLimited / non-NoVideo reason
AND ACTIVE session exists
→ AUTHENTICATED_RELAY
```

This includes technical failures such as:

- LSD/bootstrap failure;
- malformed API JSON;
- network/API errors;
- platform schema changes.

That is broader than the V3 contract and broader than the current Downstream-AV reference rationale.

## Required fix

Introduce explicit auth-fallback eligibility.

### Instagram

Authenticated fallback should occur only when there is reference-backed evidence such as:

- ruling says Restricted Video / login required;
- long private/follower-only shortcode condition;
- target webpage redirects to login;
- anonymous Relay/page path returns a clear login-gated restriction.

Do **not** auth-fallback on generic:

- NETWORK;
- PARSE_ERROR;
- API_ERROR / transient 5xx;
- LSD missing;
- PLATFORM_CHANGED / unsupported layout.

Those should remain anonymous technical failures and flow to the existing secondary-engine fallback where permitted.

### Threads

Authenticated Relay fallback should occur only for a reference-backed case where authenticated page data may legitimately reveal more, e.g.:

- API response `data == null` with the documented unavailable/restricted Relay error form;
- quote/repost/login-walled condition established from current reference/page evidence.

Do not use auth cookies merely because Barcelona had a technical transport/parser/bootstrap failure.

If necessary, add a narrow internal error/reason such as:

```text
THREADS_AUTH_FALLBACK_ELIGIBLE
```

without changing the public error taxonomy more than needed.

## Required tests

Instagram:

- ACTIVE session + anonymous technical/network failure => authenticated API **not called**.
- ACTIVE session + explicit Restricted/LoginRequired => authenticated API called.
- ACTIVE session + target login redirect => authenticated API called.

Threads:

- ACTIVE session + LSD/bootstrap failure => authenticated Relay **not called**.
- ACTIVE session + malformed GraphQL JSON => authenticated Relay **not called**.
- ACTIVE session + reference-backed unavailable/restricted response => authenticated Relay called.

Keep existing tests showing public anonymous success never calls auth.

---

# BLOCKER 2 — Instagram Relay “exact provenance” parser still does not prove RelayPrefetchedStreamCache provenance

Current upstream yt-dlp traversal explicitly requires the enclosing entry to satisfy:

```text
v[0] == "RelayPrefetchedStreamCache"
```

before accepting:

```text
__bbox.result.data.xig_polaris_media
→ if_not_gated_logged_out
```

## Current Android issue

The new dedicated function is named/annotated as RelayPrefetchedStreamCache provenance, but implementation recursively searches for **any** object containing:

```text
__bbox.result.data.xig_polaris_media
```

without checking that it is actually underneath a `RelayPrefetchedStreamCache` entry.

Therefore an unrelated/recommended `__bbox` cache object can still be accepted as target provenance.

There is a second mismatch:

```kotlin
val gated = polarisMedia.optJSONObject("if_not_gated_logged_out") ?: polarisMedia
```

Current upstream only uses `if_not_gated_logged_out` as product info in this logged-out Relay path. Falling back to the outer `polarisMedia` can bypass the gating contract.

## Required fix

Make this a truly dedicated structural parser:

1. find a Relay `require` entry whose first element/name is exactly:
   `RelayPrefetchedStreamCache`;
2. only within that entry traverse to:
   `__bbox.result.data.xig_polaris_media`;
3. require `if_not_gated_logged_out` for the anonymous product object;
4. only then allow the narrow identity-less target-provenance exception.

Do not recursively accept arbitrary `__bbox.result.data.xig_polaris_media` elsewhere on the page.

## Required tests

- exact RelayPrefetchedStreamCache entry + target product => success;
- identical `__bbox.result.data.xig_polaris_media` under an unrelated cache/container name => reject;
- RelayPrefetchedStreamCache with outer `xig_polaris_media` but missing/null `if_not_gated_logged_out` => do not extract anonymous media from the outer object;
- unrelated identity-less media remains rejected.

---

# BLOCKER 3 — Session validation still has weak/non-reference proof paths

## Instagram

Current validator structurally parses JSON, which is an improvement, but considers identity credible when either:

```text
pk is present
OR
username is present
```

The Round 3 contract required a credible authenticated user **ID**.

A username string by itself should not be enough to promote session state to ACTIVE.

### Required Instagram rule

For the current `accounts/current_user` response:

- require `status == "ok"`;
- require a non-null user object;
- require a credible positive/non-empty user ID (`pk` or current reference-equivalent ID);
- username may be supplemental, not the sole auth proof.

If HTTP 200 lacks that proof, remain CONFIGURED.

## Threads

Current validator improved to require both a token and user id, but token detection still includes:

```text
any "token":"AQ..." anywhere
```

and user-id detection includes broader fallback marker names not established by the current Downstream-AV reference.

The current reference specifically derives:

```text
fb_dtsg:
  DTSGInitialData token

user id:
  ACCOUNT_ID / USER_ID / IG_USER_EIMU
  OR ds_user_id cookie
```

The Android validator currently does not use the already imported `ds_user_id` cookie as the reference does.

### Required Threads rule

Align validation proof with the current reference:

- DTSG proof must come from the structured `DTSGInitialData` / explicit fb_dtsg source, not arbitrary AQ-looking token text;
- user id must come from current documented page markers or the platform-scoped `ds_user_id` cookie;
- require both DTSG + credible user id.

## Required tests

Instagram:

- status ok + username only + no pk/id => remains CONFIGURED;
- status ok + credible user id => ACTIVE.

Threads:

- arbitrary `"token":"AQ..."` + user id, without DTSGInitialData/fb_dtsg => remains CONFIGURED;
- DTSG token + no page user id but valid `ds_user_id` cookie => ACTIVE;
- DTSG token + documented page user id => ACTIVE.

---

# BLOCKER 4 — Instagram mixed login-wall classification regressed to “any redirect means LOGIN_REQUIRED”

Earlier work deliberately fixed this behavior:

> terminal LOGIN_REQUIRED should only be returned when anonymous evidence is consistently login-gated; a mixed login-wall + technical/parser failure should remain fallback-eligible technical.

## Current Android issue

Current code sets:

```text
htmlLoginRedirected = true
```

if **any** anonymous profile lands on a login URL.

Final classification then does:

```text
if (allProfilesLoginGated || htmlLoginRedirected)
→ LOGIN_REQUIRED
```

So one DESKTOP login redirect plus MOBILE/CRAWLER technical failures is now enough to return terminal LOGIN_REQUIRED.

This reintroduces the exact mixed-profile collapse that was fixed earlier.

## Required fix

A single login redirect may make the request **auth-fallback eligible** when an ACTIVE session exists, but it must not by itself prove the final anonymous result is LOGIN_REQUIRED when other profiles provide conflicting technical evidence.

Separate these concepts:

```text
authFallbackEligible
!=
finalAnonymousLoginRequired
```

Final LOGIN_REQUIRED without a usable authenticated session should require consistent/strong evidence, such as:

- explicit ruling restriction;
- explicit private/follower-only condition;
- all meaningful anonymous profiles login-gated;
- current-reference terminal restriction.

If one profile redirects to login while other profiles fail technically, preserve a fallback-eligible technical result.

## Required tests

- DESKTOP final URL redirects to login + MOBILE technical + CRAWLER technical => final technical/fallback-eligible, not terminal LOGIN_REQUIRED.
- all anonymous profiles login-redirect/login-gated => LOGIN_REQUIRED.
- explicit ruling Restricted Video => LOGIN_REQUIRED.
- with ACTIVE session, one proven login redirect may trigger authenticated fallback without changing the no-session mixed-evidence classification rule.

---

# Resolved findings — do not regress

The following are considered resolved and should remain unchanged:

- base-page Instagram LSD bootstrap;
- `__eqmc.l` parsing;
- no fake LSD;
- non-fatal ruling preflight;
- anonymous-first ordering;
- Instagram anonymous cookie isolation;
- Threads anonymous cookie isolation;
- Threads `threads.com` endpoint/origin;
- Threads anti-JSON prefix handling;
- CONFIGURED is not extraction-authenticated;
- platform-specific session import;
- X requires `auth_token + ct0`;
- no random X ct0;
- HttpOnly Netscape import;
- generic `xig_polaris_media` identity matching is now strict.

---

# Required local verification

After fixing all four blockers:

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

Blocker 1 auth fallback gating:
- Instagram eligibility:
- Threads eligibility:
- tests:

Blocker 2 Relay provenance:
- exact RelayPrefetchedStreamCache detection:
- if_not_gated_logged_out requirement:
- tests:

Blocker 3 validation:
- Instagram ID proof:
- Threads DTSG + user ID/ds_user_id proof:
- tests:

Blocker 4 mixed login evidence:
- auth-fallback eligibility:
- final anonymous classification:
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

Only after the next ChatGPT review explicitly says **NO BLOCKING FINDINGS / READY FOR CI** may Android CI be manually dispatched.
