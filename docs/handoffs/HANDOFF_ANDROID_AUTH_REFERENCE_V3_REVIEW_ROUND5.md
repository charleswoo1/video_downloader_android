# HANDOFF — V3 Review Round 5: Threads Restricted-Response Classification Finalization

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Reviewed implementation commit: `46a13d19d3e00e9f95f7b8a1b715e89dfb434462`

Current branch head also contains the later CI-only retention change:

`c445f61b7a7fa881a69c116af243dda3d5032535`

Status: **narrow review correction / local validation only / DO NOT run CI yet / NOT merge, tag, or release authorization**

Governing contracts remain:

- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`
- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3_REVIEW_ROUND4.md`

Current CI policy remains manual-only. Artifact retention is now 2 days.

Do not manually trigger GitHub Actions until the next review explicitly says **NO BLOCKING FINDINGS / READY FOR CI**.

---

# Review summary

Round 4 resolved the Instagram blockers and most session-validation concerns correctly:

- Instagram authenticated fallback is now gated by explicit auth evidence rather than every technical failure.
- Instagram mixed login-wall classification is again separated from auth-fallback eligibility.
- Instagram Relay provenance now requires an enclosing `RelayPrefetchedStreamCache` entry.
- Anonymous Relay extraction requires `if_not_gated_logged_out`.
- Instagram ACTIVE validation now requires a credible numeric `pk`, not username alone.
- Threads generic AQ-token validation fallback was removed.
- Threads can use `ds_user_id` as current Downstream-AV does.
- Threads technical bootstrap / malformed JSON failures no longer automatically invoke authenticated Relay.

The remaining findings are now concentrated in the Threads response classifier and one reference-fidelity detail in Threads validation.

---

# BLOCKER 1 — Threads marks every “target not found” response as auth-fallback eligible

## Current reference rechecked

Current:

`boneless3vil/Downstream-AV/yt_dlp_plugins/extractor/threads.py`

distinguishes these cases:

### A. Top-level platform error

```text
response.error
→ expected Threads API error
```

This is not automatically an authentication fallback signal.

### B. Relay unavailable/restricted response

```text
response.data == null
with errors=[...]
→ post unavailable; may be deleted or login-restricted
→ this is the reference-backed restricted/unavailable condition
```

### C. Response has data/thread, but target post is absent

```text
response.data exists
but target code/pk not present
→ "Threads returned the thread without this post"
→ target-not-found/deleted condition
```

This is distinct from B.

## Current Android issue

Current `fetchBarcelonaGraphQL()` does:

```kotlin
val matchingPost = findTargetPostInGraphQL(json, shortcode, pk)
    ?: TargetNotInPageData(
        internalReason = "THREADS_AUTH_FALLBACK_ELIGIBLE"
    )
```

Therefore **all** of the following become authenticated-fallback eligible:

- `data == null` + documented errors;
- top-level `error`;
- normal non-null data with empty edges;
- non-null thread containing a different/unrelated post;
- target-isolation mismatch.

This is still broader than the current reference and the Round 4 contract.

The current regression test also encodes the wrong condition:

`extractMediaInfo_activeSession_barcelonaFails_fallsBackToAuthenticatedRelay`

currently uses:

```json
{"data":{"data":{"edges":[]}}}
```

and expects authenticated Relay. That response has non-null data and is not the reference-backed `data == null` restricted condition.

## Required fix

Classify the raw JSON **before** calling `findTargetPostInGraphQL`.

Required behavior:

### Top-level error

If current reference-equivalent:

```text
json.error exists
```

return an expected API/platform error with a non-auth-fallback internal reason.

Do not send session cookies solely because of this.

### Explicit unavailable/restricted Relay response

Only mark:

```text
THREADS_AUTH_FALLBACK_ELIGIBLE
```

when the current reference-backed unavailable/restricted shape is present, principally:

```text
top-level data == null
AND
documented errors[] / unavailable response context
```

Do not infer this merely from “target was not found”.

### Non-null data but target absent

If:

```text
top-level data != null
AND
findTargetPostInGraphQL(...) == null
```

return a distinct target-not-found reason such as:

```text
BARCELONA_TARGET_NOT_FOUND
```

and do **not** use authenticated Relay automatically.

Strict target isolation must remain unchanged.

## Required tests

Replace/add tests proving:

1. ACTIVE session + `{"data":null,"errors":[...]}` => authenticated Relay fallback may be called.
2. ACTIVE session + `{"data":{"data":{"edges":[]}}}` => authenticated Relay is **not** called.
3. ACTIVE session + non-null data containing unrelated/wrong target => authenticated Relay is **not** called.
4. ACTIVE session + top-level `error` => authenticated Relay is **not** called.
5. Existing LSD/bootstrap/malformed JSON “no auth fallback” tests remain green.

---

# BLOCKER 2 — Threads auth-eligible response without an ACTIVE session loses the useful restriction guidance

Current code computes:

```text
isAuthFallbackEligible
```

but if no ACTIVE Threads session exists it simply continues through Desktop/Mobile/Crawler fallback.

If those lower anonymous fallbacks fail technically, the final result can become a generic parser error and discard the useful fact that Barcelona already returned the current-reference unavailable/restricted response.

That does not satisfy the V3 requirement for a clear user-facing session/auth hint when anonymous access is exhausted.

## Required fix

Preserve the auth-eligible/unavailable response evidence through the remaining anonymous fallback attempts.

Preferred behavior:

```text
Barcelona returns reference-backed unavailable/restricted response
→ no ACTIVE session
→ optional lower anonymous fallbacks may still run
→ if none succeeds:
   return a clear Threads unavailable/restricted error:
   "Threads reports this post as unavailable; it may be deleted or require login.
    If it opens in your browser, import a Threads session."
```

Do not falsely state that login is definitely required when the current reference itself says the post may also be deleted.

Use a structured error/internal reason that:

- preserves the current reference uncertainty;
- gives the user the session action when applicable;
- does not cause unrelated media fallback;
- does not silently collapse into generic PARSE_ERROR.

If the existing router should still try a technically safe secondary engine, preserve that intentionally and document it in the test; do not lose the restricted/unavailable evidence.

## Required tests

- no session + `data == null/errors[]` + all anonymous HTML fallbacks fail => final error retains restricted/unavailable/session guidance, not generic parser failure;
- no session + same API response but a later anonymous target-page fallback succeeds => extraction succeeds;
- no session + non-null data/target missing => remains target-not-found, not “login required”.

---

# BLOCKER 3 — Threads page user-id regex is less faithful than the current reference

Current Downstream-AV uses approximately:

```regex
"(?:ACCOUNT_ID|USER_ID|IG_USER_EIMU)":\s*"(\d{3,})"
```

Current Android validator uses:

```regex
"(?:ACCOUNT_ID|USER_ID|IG_USER_EIMU)":"(\d+)"
```

The Android pattern does not allow whitespace between the colon and value.

A legitimate authenticated page such as:

```json
{"ACCOUNT_ID": "987654321"}
```

can therefore remain CONFIGURED even though it matches the current reference's authenticated proof.

## Required fix

Align this regex with the current reference:

- allow optional whitespace after the colon;
- require a credible numeric ID (prefer current reference's `\d{3,}`);
- retain `ds_user_id` cookie fallback.

Do not reintroduce broad markers such as `actor_id`, `currentUser`, arbitrary token strings, etc.

## Required tests

- DTSGInitialData + `"ACCOUNT_ID": "987654321"` with whitespace => ACTIVE;
- DTSGInitialData + documented compact marker => ACTIVE;
- arbitrary undocumented ID marker => remains CONFIGURED;
- ds_user_id fallback remains ACTIVE only with valid DTSG proof.

---

# Resolved Round 4 findings — do not regress

The following are considered resolved:

- Instagram auth fallback is explicit-auth-evidence gated;
- Instagram technical failures do not send account cookies;
- Instagram exact RelayPrefetchedStreamCache name check;
- Instagram logged-out Relay requires `if_not_gated_logged_out`;
- Instagram mixed login redirect + technical failures returns fallback-eligible technical;
- Instagram consistent login gating returns login-required;
- Instagram validation requires numeric user ID;
- Threads malformed JSON / LSD bootstrap failure do not invoke auth;
- Threads DTSG proof no longer accepts arbitrary AQ token;
- Threads user-id proof supports `ds_user_id`;
- all prior cookie/session/X/reference-first fixes.

---

# Required local verification

After fixing these three narrow blockers:

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
- Threads current Downstream-AV:

Blocker 1 response classification:
- top-level error:
- data-null/errors restricted response:
- non-null target-missing response:
- tests:

Blocker 2 no-session guidance:
- preserved unavailable/restricted evidence:
- anonymous fallback behavior:
- tests:

Blocker 3 validation regex:
- documented page markers:
- whitespace handling:
- ds_user_id:
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

Only after the next ChatGPT review explicitly says **NO BLOCKING FINDINGS / READY FOR CI** may `Android CI` be manually dispatched.
