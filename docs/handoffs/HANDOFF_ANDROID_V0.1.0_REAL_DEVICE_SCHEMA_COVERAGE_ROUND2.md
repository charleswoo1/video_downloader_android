# HANDOFF — Android v0.1.0 Real-Device Schema Coverage Round 2

Repository: `charleswoo1/video_downloader_android`

Target PR: **#2**

Target branch: `feat/android-v0.1.0-initial`

Starting head for this round: `11f824e1235422e906dbb0e5f7a526eea1030788`

Status: **follow-up implementation contract / owner-device evidence driven / NOT release authorization**

Read first:

1. `docs/handoffs/HANDOFF_ANDROID_V0.1.0.md`
2. `docs/handoffs/HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_REGRESSION_FIX.md`
3. This file

This file controls the **next** implementation round for mixed real-device success/failure after Run #29.

Do not reinterpret this task as “increase retries”, “add login”, or “replace native extraction with yt-dlp”. The new evidence points to **content-shape / public-payload coverage differences**.

---

## 0. Owner-device findings from Run #29

The owner tested multiple public posts back-to-back on the same Android device and network.

### Overall result

- Instagram: **most tested videos now work**, but some specific Reels consistently fail.
- Threads: **some posts work, some consistently fail**.
- X: **only a minority work; most tested video posts fail**.

This is important:

> Adjacent posts can alternate PASS / FAIL in the same session.

Therefore do not assume a simple global request-frequency throttle.

Per-content anti-abuse or anonymous-surface differences are still possible, but any claim of “rate limiting” must now be supported by actual HTTP / extractor evidence, not by a generic text mapping.

---

# 1. New concrete real-device cases

## 1.1 Instagram — PASS

Input:

`https://www.instagram.com/reel/DdcwYWzxZI7/?stkn=eG9rcXVtMDlya2p0`

Observed:

- Analyze: PASS
- Native engine: `NativeInstagramEngine (DESKTOP)`
- fallback: no
- download options displayed
- native path works for this content shape

This proves the current native Instagram stack itself is viable on the same device/session.

## 1.2 Instagram — deterministic FAIL shown as “Rate Limited”

Input:

`https://www.instagram.com/reel/DcvXZ-8PnbK/?stkn=MXAwc2RhOHdzZ3RyYg==`

Observed:

- Native: `NativeInstagramEngine: PARSE_ERROR`
- Router falls back to `YtDlpDownloadEngine`
- UI then displays:
  `存取頻率受限 (Rate Limited)，請稍候再試`

Owner observation:

- Other Instagram Reels can succeed immediately before/after.
- Returning to this same Reel later still produces the same failure.

Engineering conclusion:

> The current UI string is **not evidence of a global rate limit**.

Current `YtDlpErrorParser` classifies a message as `RATE_LIMITED` when it merely contains:

- `rate-limit`
- `rate limit`
- **or `Please wait a few minutes`**

It does not require HTTP 429.

So this case is currently:

```text
native parser cannot resolve this content shape
    ↓
yt-dlp fallback receives/reports an Instagram refusal/message
    ↓
generic text mapper labels it RATE_LIMITED
```

Do not fix this by adding sleep/backoff loops first.

## 1.3 Threads — PASS

Input:

`https://www.threads.com/share/BBmiaNprFr/`

Observed:

- Analyze: PASS
- Download: PASS
- saved file: PASS
- engine: `NativeThreadsEngine (DESKTOP)`
- fallback: no

Again, this proves that share-link resolution and native Threads download work for at least one current public payload shape.

## 1.4 Threads — FAIL #1

Input:

`https://www.threads.com/share/BAYKJFlj1r/`

Resolved target shown by the app:

`DdcJ4wwkjVQ`

Observed:

- Native: `NativeThreadsEngine: PARSE_ERROR`
- fallback: `YtDlpDownloadEngine`
- fallback error is presented as private/login/not-found style wording

Owner confirms the media is playable in the official app.

Treat this as a parser / anonymous-public-shape regression until evidence proves otherwise.

## 1.5 Threads — FAIL #2

Input:

`https://www.threads.com/share/BAZPPSvlas/`

Resolved target shown by the app:

`DdbxeeBjzNO`

Observed:

- Native: `NativeThreadsEngine: PARSE_ERROR`
- fallback: `YtDlpDownloadEngine`
- fallback reports private/login/not-found style failure

Same rule: this fallback wording is not proof that the target is actually private or missing.

## 1.6 X — FAIL representative case

Input:

`https://x.com/SmallQQQQQ/status/2099089385958645960`

Observed:

- Native: `NativeXEngine: PAGE_VARIANT_UNSUPPORTED`
- fallback: `YtDlpDownloadEngine`
- yt-dlp: `No video could be found in this tweet`

Owner observation:

- Only a small fraction of tested X videos currently succeed.
- Most fail.

Important implementation detail:

`PlatformExtractionError.ProvisionalUnavailable` currently uses the public code:

`PAGE_VARIANT_UNSUPPORTED`

Therefore the UI label `PAGE_VARIANT_UNSUPPORTED` is overloaded. It does **not** tell us whether the real failure was:

- GraphQL `TweetUnavailable/TweetTombstone`
- missing `legacy`
- a wrapper variant
- target media stored under quoted/retweeted/card data
- HTML fallback missing `window.__INITIAL_STATE__`
- another parser variant

Do not add speculative retry loops without first exposing the internal stage/reason.

---

# 2. Mission for Round 2

The goal is no longer just error-classification cleanup.

The goal is:

1. make real-device failures diagnostically actionable;
2. identify the exact payload-shape differences between working and failing posts;
3. add **targeted** parser support for those observed shapes;
4. keep strict target isolation;
5. stop misleading terminal/fallback labels from hiding the native root cause.

Do not broaden the app architecture.

---

# 3. Phase A — mandatory structured sanitized diagnostics

Implement this **before guessing additional parser paths**.

The current UI trace is too coarse.

Create a structured diagnostic summary that can be surfaced in the existing Runtime Diagnostics / engine trace area or copied from it.

Do not dump full HTML or full JSON.

Do not log secrets.

## 3.1 Common fields

For each native attempt record:

- platform
- input post ID / shortcode / status ID
- request profile
- HTTP status
- content-type
- response-body size bucket, e.g. `<10KB`, `10-100KB`, `>100KB`
- final host + path only
- redirect occurred: yes/no
- parser stage
- error class
- fallback attempted: yes/no

Never include:

- cookies
- bearer token
- guest token
- authorization
- full signed media URL
- full `stkn`
- full HTML
- full JSON

## 3.2 Instagram diagnostic fingerprint

For every DESKTOP / MOBILE / CRAWLER attempt record:

- script count:
  - application/json
  - data-sjs
  - generic candidate scripts
- target shortcode appears in raw page: yes/no
- target shortcode appears after bounded nested JSON decode: yes/no
- target wrapper found: yes/no
- actual validated media node found: yes/no
- matched object key names only, sorted
- relevant marker presence:
  - `xdt_shortcode_media`
  - `xdt_api__v1__media__shortcode__web_info`
  - `if_not_gated_logged_out`
  - `video_versions`
  - `video_url`
  - `carousel_media`
  - `image_versions2`
  - `media_type`
  - `is_video`
- restriction phrase classification if any

Do not log values of signed URLs/tokens.

## 3.3 Threads diagnostic fingerprint

For share links record:

- original: `/share/<token>`
- redirect/canonical resolved: yes/no
- canonical target code
- canonical host + path only

For parser profiles record:

- script counts by source
- target code raw occurrence: yes/no
- target code decoded occurrence: yes/no
- wrapper found: yes/no
- validated media node found: yes/no
- matched wrapper key names only
- nearest child-container key names only
- media markers:
  - `video_versions`
  - `video_dash_manifest`
  - `carousel_media`
  - `linked_inline_media`
  - `link_preview_response`
  - `quoted_post`
  - `quoted_attachment_post`
  - `text_post_app_info`

This data is specifically needed for:

- `DdcJ4wwkjVQ`
- `DdbxeeBjzNO`

## 3.4 X diagnostic fingerprint

For GraphQL record:

- HTTP status
- `result.__typename`
- wrapper chain, names only
- presence flags:
  - `result.legacy`
  - `result.tweet`
  - `result.tweet.legacy`
  - `quoted_status_result`
  - `retweeted_status_result`
  - `extended_entities`
  - `entities`
  - `card`
  - `unified_card`
  - `note_tweet`
- direct media count
- direct media types only
- whether explicit video/animated_gif was seen
- whether usable MP4 variant was seen
- provisional unavailable typename if applicable
- retry attempted: yes/no

For HTML fallback record:

- HTTP status
- body-size bucket
- `window.__INITIAL_STATE__` present: yes/no
- target status ID raw occurrence: yes/no
- known public-state marker presence, names only
- parser outcome

This is required because `PAGE_VARIANT_UNSUPPORTED` currently hides too many distinct X failure modes.

---

# 4. Phase B — fix error presentation so it does not misdiagnose the root cause

## 4.1 Instagram “Rate Limited”

Current mapper must not claim rate limiting based only on `Please wait a few minutes`.

Required behavior:

### Confirmed rate limit

Use `RATE_LIMITED` when evidence includes one of:

- HTTP 429
- explicit `Too Many Requests`
- another unambiguous rate-limit response marker

### Ambiguous Instagram fallback refusal

If yt-dlp only reports something like:

`Please wait a few minutes before you try again`

without confirmed HTTP 429:

- do not present it as definite global rate limiting;
- classify as fallback extractor/access refusal;
- preserve the sanitized primary yt-dlp error in diagnostics;
- user-facing text should not tell the user that waiting will necessarily solve it.

Example wording:

`Instagram 備援解析遭平台拒絕；此內容可能使用不同的公開頁面資料格式`

Do not hide the native root cause:

`NativeInstagramEngine: PARSE_ERROR`

## 4.2 Threads fallback wording

The existing yt-dlp error mapping combines:

- not found
- deleted
- private
- login-gated

into one user message.

When native engine already produced `PARSE_ERROR`, the fallback message must not be treated as authoritative content classification.

Keep both in diagnostics:

```text
primary_native = PARSE_ERROR
fallback = <sanitized yt-dlp/plugin category>
```

Do not rewrite the native result as PRIVATE/DELETED unless independent evidence supports it.

## 4.3 X public trace reason

Keep the existing public error taxonomy if desired, but expose an internal reason:

Examples:

- `PROVISIONAL_UNAVAILABLE:TweetUnavailable`
- `PROVISIONAL_UNAVAILABLE:TweetTombstone`
- `GRAPHQL_MISSING_LEGACY`
- `GRAPHQL_TARGET_WRAPPER_UNSUPPORTED`
- `GRAPHQL_NO_DIRECT_MEDIA`
- `HTML_INITIAL_STATE_MISSING`
- `HTML_TARGET_NOT_FOUND`
- `MEDIA_VARIANT_UNSUPPORTED`

The screenshot must no longer collapse all of these into only:

`PAGE_VARIANT_UNSUPPORTED`

---

# 5. Phase C — targeted parser coverage after diagnostics

Do not add broad “search any video URL anywhere on the page” logic.

That would create false-media selection.

## 5.1 Instagram

Use the failing `DcvXZ-8PnbK` diagnostic fingerprint to determine the missing container/path.

Then add only the observed missing public payload shape.

Maintain:

- shortcode/id validation
- target isolation
- DESKTOP → MOBILE → CRAWLER escalation
- native primary
- no login/cookies

Do not fix this case by relying on yt-dlp.

## 5.2 Threads

Use diagnostics from:

- `DdcJ4wwkjVQ`
- `DdbxeeBjzNO`

Compare against the working `BBmiaNprFr`.

The important comparison is:

```text
same device/session
same NativeThreadsEngine
working target shape
vs
failing target shape
```

Add parser support for the exact differing target-associated container(s).

Preserve strict target isolation.

Do not select:

- recommended posts
- neighboring thread items
- unrelated quoted media
- first arbitrary page video

unless the payload semantics explicitly associate that nested media with the target post.

## 5.3 X

Because failure rate is now high, do not treat the representative failure as one isolated edge case.

First inspect the structured GraphQL fingerprint.

Then support observed target-associated variants, which may include one or more of:

- direct `Tweet`
- `TweetWithVisibilityResults`
- target-associated quoted status result
- target-associated retweeted status result
- target-associated card / unified-card media

Only implement a variant when diagnostics or a sanitized fixture proves it is relevant.

If visible media belongs to a quoted/retweeted target-associated tweet and the product expectation is to download the video visible in the official X post, resolve that nested tweet deterministically.

Do not traverse unrelated timeline/recommendation data.

The current HTML fallback relies heavily on `window.__INITIAL_STATE__`. If real-device diagnostics show that current public X pages no longer contain it, add the smallest observed modern public-page fallback. Do not invent a generic scraper.

---

# 6. Fallback policy for this round

Native resolver remains primary.

## Instagram

```text
NativeInstagramEngine
  ↓ technical only
yt-dlp fallback
```

But fallback failure must not overwrite the native root-cause diagnostics.

## Threads

```text
NativeThreadsEngine
  ↓ technical only
existing fallback path
```

Do not assume yt-dlp/plugin “private/not found” is authoritative after a native parser failure.

## X

```text
NativeXEngine
  GraphQL
  → bounded session refresh when appropriate
  → HTML/public fallback
  ↓ technical only
yt-dlp fallback
```

yt-dlp `No video could be found` is not proof of no visible media when the owner confirms the official app displays playable video.

---

# 7. Tests required

No live network calls in CI.

## Instagram

Add sanitized fixtures/tests for:

1. working shape equivalent to `DdcwYWzxZI7`
2. failing-shape fingerprint equivalent to `DcvXZ-8PnbK`
3. confirmed HTTP 429 => RATE_LIMITED
4. `Please wait a few minutes` without HTTP 429 => not definitive RATE_LIMITED
5. primary native PARSE_ERROR retained in router diagnostics after fallback failure

## Threads

Add fixtures/tests for:

1. working shape equivalent to `BBmiaNprFr`
2. target `DdcJ4wwkjVQ`
3. target `DdbxeeBjzNO`
4. target isolation against recommendation/feed neighbor
5. fallback private/not-found wording does not erase native PARSE_ERROR diagnostic

## X

Add fixtures/tests for every target-associated shape actually found in device diagnostics.

At minimum add a diagnostic test proving that these distinct failures remain distinguishable:

- provisional unavailable
- missing legacy/wrapper mismatch
- HTML initial-state missing
- no direct media
- unsupported video rendition

If the representative failing post proves to contain quoted/retweeted/card media, add a sanitized success fixture for that exact relationship.

---

# 8. Real-device gate after implementation

Build one new APK after diagnostics + targeted parser fixes.

Re-test in mixed order rather than platform batches.

Suggested sequence:

1. Instagram known PASS
2. Instagram known FAIL
3. Threads known PASS
4. Threads FAIL #1
5. X known PASS
6. X representative FAIL
7. Threads FAIL #2
8. Instagram known PASS again

This sequence helps distinguish true session-level throttling from deterministic content-shape failures.

For every case capture:

- input URL
- native engine
- profile sequence
- structured diagnostic fingerprint
- fallback attempted
- final result
- download result
- audio present

---

# 9. Scope boundaries

Do not:

- add account login
- add WebView auth
- import browser cookies
- add server backend
- add proxy rotation
- add sleep/backoff as the primary fix
- switch IG/Threads/X to yt-dlp-first
- change unrelated UI
- change YouTube/Facebook/TikTok behavior
- create another PR
- merge
- tag
- release

---

# 10. Completion criteria for Round 2

Implementation is ready for another owner-device run only when:

- [ ] structured sanitized native diagnostics implemented
- [ ] Instagram ambiguous “Please wait” no longer falsely presented as confirmed rate limit
- [ ] failing Instagram shape has a deterministic offline fixture
- [ ] failing Threads shapes have deterministic offline fixtures
- [ ] X internal failure reason is no longer hidden by generic PAGE_VARIANT_UNSUPPORTED
- [ ] observed X target-associated media variant(s) are covered
- [ ] target isolation tests remain green
- [ ] `./gradlew test` PASS
- [ ] `./gradlew lintDebug` PASS
- [ ] `./gradlew assembleDebug` PASS
- [ ] CI PASS
- [ ] debug APK artifact uploaded
- [ ] PR #2 still open
- [ ] no tag
- [ ] no release

Owner-device acceptance remains required.

---

# 11. Required agent report

Use this structure:

```text
Contract:
- HANDOFF_ANDROID_V0.1.0.md
- HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_REGRESSION_FIX.md
- HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_SCHEMA_COVERAGE_ROUND2.md

Starting head:
Final head:

Diagnostics:
- common structured fields:
- Instagram fingerprint:
- Threads fingerprint:
- X fingerprint:
- secrets/full payload exposure: NONE

Instagram:
- DdcwYWzxZI7 working-shape protection:
- DcvXZ-8PnbK root cause:
- parser change:
- Rate Limited classification change:
- tests:

Threads:
- BBmiaNprFr working-shape protection:
- DdcJ4wwkjVQ root cause:
- DdbxeeBjzNO root cause:
- parser changes:
- target isolation:
- tests:

X:
- representative failure 2099089385958645960 internal reason:
- GraphQL shape:
- HTML fallback shape:
- target-associated media relationship:
- parser/fallback changes:
- tests:

Regression:
- YouTube:
- Facebook:
- existing IG pass:
- existing Threads pass:
- existing X pass:

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
Tag: NO
Release: NO
```
