# HANDOFF — Android v0.1.0 X Effective Typename Diagnostic Fix

Repository: `charleswoo1/video_downloader_android`

Target PR: **#2**

Target branch: `feat/android-v0.1.0-initial`

Starting head for this handoff: `3e2a7a6b6202d261a09246c2e4ffc414e819b431`

Status: **narrow follow-up diagnostic fix / NOT release authorization**

Read these contracts first:

1. `docs/handoffs/HANDOFF_ANDROID_V0.1.0.md`
2. `docs/handoffs/HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_REGRESSION_FIX.md`
3. `docs/handoffs/HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_SCHEMA_COVERAGE_ROUND2.md`
4. This file

This file controls only the remaining X diagnostic correctness issue identified after CI Run #34.

Do not broaden this task.

---

## 1. Current status

CI Run #34 is green.

The previous Round 2 diagnostic blockers are already fixed:

- shared HTTP log redaction covers `stkn`, `sig/signature`, guest/session/auth/token-like values;
- Instagram distinguishes `FETCH_FAILED`, `EMPTY_BODY`, and parser failures;
- Instagram preserves DESKTOP → MOBILE → CRAWLER diagnostic history;
- X GraphQL diagnostics expose transport status sequence such as `403→200`;
- X GraphQL diagnostics expose `auth_refresh_attempted`;
- X GraphQL / HTML diagnostics include content type, body-size bucket, final host/path, redirect;
- X quoted-status detection follows the unwrapped tweet object;
- X unified-card diagnostics include `legacy.card.legacy`;
- Threads preserves empty/failed profile attempts;
- yt-dlp fallback category and sanitized primary error are preserved.

Do not rework those areas unless a failing test caused by this exact change requires a narrow adjustment.

---

# 2. Remaining blocker

## X `TweetWithVisibilityResults` diagnostic typename does not follow parser semantics

Current parser behavior in `NativeXEngine.parseGraphQLTweet()` is effectively:

```kotlin
var resultObj = tweetResult.optJSONObject("result") ?: ...
if (resultObj.optString("__typename") == "TweetWithVisibilityResults") {
    resultObj = resultObj.optJSONObject("tweet") ?: resultObj
}
val typename = resultObj.optString("__typename")
```

Therefore the parser's effective target object can be:

```text
TweetWithVisibilityResults
└─ tweet
   └─ TweetUnavailable
```

and the parser correctly returns:

```text
ProvisionalUnavailable(TweetUnavailable)
```

or:

```text
GRAPHQL_PROVISIONAL_UNAVAILABLE:TweetUnavailable
```

However, `computeGraphQLDiagnostics()` currently derives the reported typename primarily from the outer/raw result object:

```kotlin
val typename = rawResultObj?.optString("__typename")
```

and computes:

```kotlin
val provTypename =
    if (typename in listOf("TweetUnavailable", "TweetTombstone")) typename else "none"
```

So the diagnostic fingerprint can incorrectly report:

```text
typename=TweetWithVisibilityResults
wrapper=tweetResult->TweetWithVisibilityResults->tweet
provisional_typename=none
```

while the parser actually classified the inner tweet as:

```text
TweetUnavailable
```

This is unacceptable for the next owner-device diagnostic run because the purpose of the fingerprint is to distinguish:

- provisional/session availability behavior;
- unsupported GraphQL wrapper/schema;
- target-associated nested media;
- media variant unsupported;
- HTML fallback mismatch.

---

# 3. Required implementation

Modify only the X diagnostic representation necessary to make the fingerprint follow the same effective object semantics as `parseGraphQLTweet()`.

## 3.1 Preserve both outer and effective typename

The diagnostic model must distinguish:

### Outer/raw typename

The typename directly under:

```text
data
└─ tweetResult
   └─ result
      └─ __typename
```

Example:

```text
TweetWithVisibilityResults
```

### Effective/unwrapped typename

If outer typename is `TweetWithVisibilityResults`, inspect:

```text
result.tweet.__typename
```

Otherwise effective typename is the same as outer typename.

Example:

```text
outer_typename=TweetWithVisibilityResults
effective_typename=TweetUnavailable
```

Do not discard the outer typename.

---

## 3.2 Compute provisional state from the effective object

`provisional_typename` must be derived from the same effective object the parser uses.

Required rule:

```text
effective_typename == TweetUnavailable
    → provisional_typename=TweetUnavailable

effective_typename == TweetTombstone
    → provisional_typename=TweetTombstone

otherwise
    → provisional_typename=none
```

Do not derive provisional state solely from the outer wrapper typename.

---

## 3.3 Keep wrapper chain

Preserve the existing wrapper-chain information.

For visibility wrapper:

```text
wrapper=tweetResult->TweetWithVisibilityResults->tweet
```

For direct Tweet:

```text
wrapper=tweetResult->Tweet
```

For direct unavailable/tombstone:

```text
wrapper=tweetResult->TweetUnavailable
```

Do not remove wrapper-chain reporting.

---

## 3.4 Make the fingerprint explicit

Update the X GraphQL fingerprint to include both names explicitly.

Preferred shape:

```text
[profile=GRAPHQL attempt=1 ...]
outer_typename=TweetWithVisibilityResults
effective_typename=TweetUnavailable
wrapper=tweetResult->TweetWithVisibilityResults->tweet
...
provisional_typename=TweetUnavailable
```

Avoid one ambiguous field simply called `typename`.

If backward compatibility requires keeping `typename=`, it may remain as an alias, but the new explicit fields must exist and tests must assert them.

---

# 4. Diagnostic/parser consistency rule

For any GraphQL payload, the following must agree:

```text
parseGraphQLTweet() effective target object
            ==
computeGraphQLDiagnostics() effective target object
```

This applies to:

- direct `Tweet`;
- direct `TweetUnavailable`;
- direct `TweetTombstone`;
- `TweetWithVisibilityResults -> tweet -> Tweet`;
- `TweetWithVisibilityResults -> tweet -> TweetUnavailable`;
- `TweetWithVisibilityResults -> tweet -> TweetTombstone`.

Do not create a second independent unwrapping algorithm with different semantics if the existing logic can be shared or mirrored narrowly.

A tiny helper is acceptable if it reduces drift, for example an internal structure containing:

```text
outer object
outer typename
effective object
effective typename
wrapper chain
```

Do not turn this into a broad X parser refactor.

---

# 5. Required regression tests

Add deterministic unit tests. No live X calls.

## Test A — visibility wrapper with unavailable inner tweet

Fixture shape:

```json
{
  "data": {
    "tweetResult": {
      "result": {
        "__typename": "TweetWithVisibilityResults",
        "tweet": {
          "__typename": "TweetUnavailable"
        }
      }
    }
  }
}
```

Assertions:

- `outerTypename == "TweetWithVisibilityResults"`
- `effectiveTypename == "TweetUnavailable"`
- `wrapperChain == "tweetResult->TweetWithVisibilityResults->tweet"`
- `provisionalTypename == "TweetUnavailable"`
- fingerprint contains:
  - `outer_typename=TweetWithVisibilityResults`
  - `effective_typename=TweetUnavailable`
  - `provisional_typename=TweetUnavailable`
- `parseGraphQLTweet()` returns `PlatformExtractionError.ProvisionalUnavailable`
- parser error typename is `TweetUnavailable`

## Test B — visibility wrapper with normal Tweet

Fixture shape:

```json
{
  "data": {
    "tweetResult": {
      "result": {
        "__typename": "TweetWithVisibilityResults",
        "tweet": {
          "__typename": "Tweet",
          "rest_id": "123",
          "legacy": {
            "full_text": "test"
          }
        }
      }
    }
  }
}
```

Assertions:

- `outerTypename == "TweetWithVisibilityResults"`
- `effectiveTypename == "Tweet"`
- `provisionalTypename == "none"`
- target rest ID detection still works
- fingerprint contains both outer/effective names

The parser may still return `NoVideo` for this fixture if it contains no video media; that is fine. This test is about diagnostic semantics.

## Test C — direct provisional typename

Fixture:

```text
result.__typename = TweetTombstone
```

Assertions:

- outer = TweetTombstone
- effective = TweetTombstone
- provisional = TweetTombstone
- wrapper = `tweetResult->TweetTombstone`

This protects the existing direct path.

---

# 6. Optional non-blocking hardening

Only after the blocking fix is complete and tests are green:

For X HTML fetch failure, the current fingerprint can show:

```text
http_status=0
outcome=NOT_FETCHED
```

It would be useful to also expose a sanitized transport exception class:

```text
transport_error=IOException
```

or:

```text
transport_error=SocketTimeoutException
```

Rules:

- class name only;
- no raw exception message if it could contain URLs/tokens;
- no stack trace;
- do not block the owner-device APK on this optional addition.

Do not let this optional item expand into another HTTP diagnostics framework.

---

# 7. Scope boundaries

Do not:

- change GraphQL endpoint selection;
- change bearer-token discovery;
- change guest-token bootstrap;
- change 401/403 retry count;
- add additional retry loops;
- add login;
- add cookies import;
- add WebView auth;
- add proxies;
- add server backend;
- change yt-dlp fallback policy;
- change Instagram or Threads logic;
- change download/FFmpeg behavior;
- change unrelated UI;
- create another PR;
- merge;
- tag;
- release.

This handoff is primarily a **diagnostic truthfulness fix**.

---

# 8. Validation gate

Run:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Then wait for GitHub Actions.

Required:

- all unit tests pass;
- no new lint blocker;
- debug APK builds;
- CI green;
- artifact uploaded.

Do not mark X extraction as fixed based on CI.

This change only makes the next real-device failure trace trustworthy.

---

# 9. Definition of done

This handoff is complete when all are true:

- [ ] outer typename is preserved;
- [ ] effective/unwrapped typename is preserved;
- [ ] provisional typename is derived from effective typename;
- [ ] wrapper chain remains visible;
- [ ] diagnostics and parser use equivalent unwrapping semantics;
- [ ] visibility-wrapper → TweetUnavailable test passes;
- [ ] visibility-wrapper → Tweet test passes;
- [ ] direct TweetTombstone test passes;
- [ ] existing X tests pass;
- [ ] Instagram/Threads tests remain unchanged and green;
- [ ] `./gradlew test` PASS;
- [ ] `./gradlew lintDebug` PASS;
- [ ] `./gradlew assembleDebug` PASS;
- [ ] CI PASS;
- [ ] debug APK artifact uploaded;
- [ ] PR #2 remains open;
- [ ] no merge;
- [ ] no tag;
- [ ] no release.

---

# 10. Required agent report

Use exactly this structure:

```text
Contract:
- HANDOFF_ANDROID_V0.1.0.md
- HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_REGRESSION_FIX.md
- HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_SCHEMA_COVERAGE_ROUND2.md
- HANDOFF_ANDROID_V0.1.0_X_EFFECTIVE_TYPENAME_DIAGNOSTIC_FIX.md

Starting head:
Final head:

X diagnostic fix:
- outer typename field:
- effective typename field:
- wrapper chain:
- provisional typename source:
- parser/diagnostic unwrapping consistency:
- optional HTML transport_error added: YES/NO

Tests:
- visibility wrapper -> TweetUnavailable:
- visibility wrapper -> Tweet:
- direct TweetTombstone:
- existing X regression tests:

Gradle:
- test:
- lintDebug:
- assembleDebug:

CI:
- run:
- conclusion:
- artifact:
- digest:

Owner-device status:
- NEEDS OWNER DEVICE TEST

Merged: NO
Tag created: NO
Release created: NO
```

Do not replace `NEEDS OWNER DEVICE TEST` with PASS based on CI or unit tests.
