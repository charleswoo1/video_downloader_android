# HANDOFF — V3 Review Round 6: Threads data=null Reference-Fidelity Final Fix

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Reviewed implementation head: `a3052ccd593a1271e881ce0dcf81ef93a8244331`

Status: **single narrow review correction / local validation only / DO NOT run CI yet / NOT merge, tag, or release authorization**

Governing contracts remain:

- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`
- `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3_REVIEW_ROUND5.md`

Current CI policy remains manual-only. Artifact retention remains 2 days.

Do not manually trigger GitHub Actions until the next review explicitly says **NO BLOCKING FINDINGS / READY FOR CI**.

---

# Review summary

Round 5 is substantially correct:

- top-level Threads `error` is now classified separately and does not invoke authenticated fallback;
- non-null `data` with target absent is now `BARCELONA_TARGET_NOT_FOUND`;
- unrelated/wrong target no longer invokes authenticated fallback;
- `data == null + errors[]` preserves unavailable/restricted evidence and can invoke auth fallback;
- no-session unavailable/restricted evidence is preserved through anonymous HTML fallbacks;
- Threads session-validation user-id regex now allows whitespace and uses documented markers / `ds_user_id`;
- previous Instagram, X, cookie, session-state, and anonymous-isolation findings remain resolved.

One current-reference mismatch remains.

---

# BLOCKER — Threads `data == null` without `errors[]` is incorrectly treated as parser failure

## Current reference rechecked

Current:

`boneless3vil/Downstream-AV/yt_dlp_plugins/extractor/threads.py`

handles:

```python
if response.get('data') is None:
    raise ExtractorError(
        'Threads reports this post as unavailable ({}) - it may be '
        'deleted or restricted to logged-in users ...'.format(
            traverse_obj(response, ('errors', 0, 'message'))
            or 'no details'),
        expected=True)
```

The important point is:

```text
response.data is None
→ unavailable/restricted classification
```

The reference does **not** require `errors[]` to exist.

When errors are missing, it still classifies the response as unavailable/restricted and uses `no details`.

## Current Android behavior

Current code separates:

```text
data == null + errors[] present
→ THREADS_AUTH_FALLBACK_ELIGIBLE

data == null + no errors[]
→ BARCELONA_NO_DATA / ParseError
```

This means a legitimate reference-shaped unavailable response such as:

```json
{"data": null}
```

will not:

- invoke authenticated Relay when an ACTIVE session exists;
- preserve the unavailable/deleted-or-login guidance when no session exists.

Instead it is treated as a technical parser failure.

That is the remaining mismatch.

---

# Required fix

Keep the top-level `error` check first.

Then classify **any** top-level missing/null `data` as the current-reference unavailable/restricted condition:

```text
json.error exists
→ BARCELONA_API_ERROR
→ NOT auth-fallback eligible

else if data is null/missing
→ THREADS_AUTH_FALLBACK_ELIGIBLE
→ details from errors[0].message when present
→ otherwise "no details"
```

Do not require `errors[]` for the auth-fallback-eligible classification.

Then keep:

```text
data non-null + target missing
→ BARCELONA_TARGET_NOT_FOUND
→ NOT auth-fallback eligible
```

No broader changes are needed.

---

# Required tests

Add/adjust tests proving:

1. ACTIVE session + `{"data":null}` with no `errors[]`
   → authenticated Relay fallback is eligible/called.

2. No session + `{"data":null}` with no `errors[]` + all anonymous HTML fallbacks fail
   → final unavailable/restricted guidance is preserved;
   → not generic ParseError.

3. `fetchBarcelonaGraphQL()` with `{"data":null}` and no errors
   → returns `THREADS_AUTH_FALLBACK_ELIGIBLE`.

4. Existing tests remain green:
   - top-level `error` does not auth-fallback;
   - `data:null + errors[]` remains auth-fallback eligible;
   - non-null empty edges remains `BARCELONA_TARGET_NOT_FOUND`;
   - unrelated target does not auth-fallback;
   - malformed JSON / LSD bootstrap do not auth-fallback.

---

# Required local verification

Run locally:

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

data=null classification:
- top-level error:
- data null with errors:
- data null without errors:
- non-null target missing:
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
