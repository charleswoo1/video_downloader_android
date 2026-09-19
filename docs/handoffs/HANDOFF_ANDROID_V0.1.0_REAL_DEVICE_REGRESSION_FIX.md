# HANDOFF — Android v0.1.0 Real-Device Resolver Regression Fix

Repository: `charleswoo1/video_downloader_android`

Target PR: **#2**

Target branch: `feat/android-v0.1.0-initial`

Head at contract creation: `e0894dc28dd1e90788db9264053b8e7582117d97`

Status: **implementation contract / follow-up regression fix / NOT release authorization**

Controlling relationship:

- Read `docs/handoffs/HANDOFF_ANDROID_V0.1.0.md` first for the overall v0.1.0 architecture and workflow.
- This file is the **controlling contract for the current real-device Instagram / Threads / X regression round**.
- If this file conflicts with older assumptions about these three regressions, **this file wins**.
- Do not reinterpret the scope beyond what is written here.

---

## 0. Owner-confirmed facts — do not dispute or reinterpret

The owner tested the current CI APK on a real Android device.

For every Instagram, Threads, and X post shown in the supplied test screenshots:

> **The media exists and is playable in the official platform app.**

Therefore, for this regression corpus, the following app results are known false classifications unless there is new, concrete evidence that the anonymous public web surface itself is unavailable:

- `NO_VIDEO`
- `DELETED_OR_NOT_FOUND`
- private/deleted/unavailable
- login-required used merely because one parser/API path failed

Do **not** explain these cases away as “the post may have been deleted/private/unavailable”.

The correct engineering assumption for this round is:

```text
OWNER CONFIRMED TARGET MEDIA EXISTS AND PLAYS
        ↓
downloader failure
        ↓
resolver / session / parser regression until proven otherwise
```

Official-app playability does not prove the anonymous web surface must expose the same payload. If anonymous web access is genuinely blocked, stop with evidence as described later. Do not invent support through login/cookies.

---

## 1. Real-device regression corpus

These are the current owner-device acceptance cases.

### 1.1 Instagram

Current observed behavior:

- Public Reel is playable in Instagram app.
- Android downloader reaches `NativeInstagramEngine`.
- It incorrectly terminates at `DESKTOP` with a “no video / possibly image-only” result.
- No MOBILE/CRAWLER escalation occurs after the false `NoVideo`.

Owner-observed Reel shortcodes include:

- `Dc3N8l8BGxp`
- `DdNHCj7zJHH`

The supplied URLs include normal Instagram Reel URLs with `stkn` query parameters. Preserve required query data; do not strip signed/share parameters blindly.

Existing regression cases in `docs/regression/META_REAL_DEVICE_CASES.md` remain relevant and must not regress.

### 1.2 Threads

Current real-device results:

1. `https://www.threads.com/share/BBk97kGup5/`
   - **PASS**
   - `NativeThreadsEngine (DESKTOP)`
   - thumbnail and real download options displayed

2. `https://www.threads.com/share/BAVLndHzC/`
   - **FAIL**
   - share URL resolves far enough to identify target post code `DdcLVjtknJ2`
   - native resolver ends in `PARSE_ERROR`
   - yt-dlp fallback also fails

3. `https://www.threads.com/share/_5-t0FGYG/`
   - **FAIL**
   - share URL resolves far enough to identify target post code `DdaQOUWkpua`
   - native resolver ends in `PARSE_ERROR`
   - yt-dlp fallback also fails

Important conclusion:

> Do **not** treat Threads `/share/` resolution as globally broken. One share case already works and the two failed cases reach target post codes. The current problem is primarily **payload/target-media parsing coverage after share resolution**.

### 1.3 X / Twitter

Current real-device results:

1. `https://x.com/mhji4vc/status/2100527337762918655`
   - **PASS**
   - Native X engine extracts thumbnail and real bitrate variants

2. `https://x.com/HOLASNBS155/status/2100528665385959506`
   - **FAIL**
   - media is playable in official X app
   - current app reports deleted/private/unavailable
   - trace shows `NativeXEngine (TERMINATED) (GRAPHQL)`
   - fallback is not attempted

Important conclusion:

> A single anonymous GraphQL `TweetUnavailable` / `TweetTombstone` response must **not** be treated as proof that this owner-confirmed playable post is deleted/private.

---

## 2. Mission

Fix the three native resolvers so the current real-device corpus is handled correctly without broad architectural changes.

Target result:

```text
Instagram → NativeInstagramEngine → actual target media → download
Threads   → NativeThreadsEngine   → actual target media → download
X         → NativeXEngine         → actual target media → download
```

yt-dlp remains a technical fallback where already allowed, but this task is **not** to make yt-dlp the primary resolver for these three platforms.

The task is complete only after:

- deterministic unit coverage is added for the newly discovered payload shapes / state transitions;
- CI passes;
- a new debug APK is produced;
- owner performs another real-device regression test.

---

## 3. Hard scope boundaries — do not expand the task

### 3.1 Allowed scope

Modify only what is necessary in:

- Instagram native resolver / parser / profile escalation;
- Threads share-to-canonical flow only if evidence shows a narrow bug;
- Threads target-post / nested-payload parser;
- X guest-session / GraphQL state handling / HTML-public-page fallback;
- shared HTTP/parser helpers if required by the above;
- resolver diagnostics;
- deterministic unit fixtures/tests;
- regression documentation directly related to this round.

### 3.2 Explicitly out of scope

Do **not**:

- redesign the app architecture;
- replace the current routing layer;
- rewrite working YouTube/Facebook/TikTok paths;
- add server-side extraction;
- add Cobalt;
- add account login;
- add WebView authentication;
- add cookies.txt import;
- capture browser credentials;
- add private API keys;
- hard-code user/session cookies;
- add a remote backend;
- rewrite UI/Compose screens except a minimal diagnostic/error text change strictly required by this task;
- change storage architecture;
- change MediaStore behavior;
- change notification architecture;
- change FFmpeg packaging unless a newly reproduced failure proves it is necessary;
- upgrade unrelated dependencies;
- create another PR;
- modify `main` directly;
- merge PR #2;
- create a tag;
- publish a Release.

If a dependency upgrade appears necessary, stop and report why before doing it unless it is a trivial patch already explicitly required by the existing handoff.

---

## 4. Source-of-truth and reference rules

Implementation source order:

1. current code in this repository;
2. current real-device evidence from owner;
3. the reference registry:
   - `docs/references/UPSTREAM_EXTRACTOR_REFERENCE_REGISTRY.md`;
4. current upstream/reference implementations listed there;
5. sanitized current public payload evidence when necessary.

Do not use the Windows repository as Android implementation source.

The Windows project may only be used as product-behavior reference.

Respect all license boundaries already documented in the registry.

For this round, the most relevant references are:

- Instagram:
  - `2Xsave/insave` (MIT)
  - `Orang-Studio/InstaDownload` (GPL-3.0, behavior/research only)
- Threads:
  - `2Xsave/trsave` (MIT)
  - bundled/public-domain Threads plugin behavior already present in this repo
- X:
  - `2Xsave/twsave` (MIT)

Do not copy GPL code or line-for-line translate it.

---

# 5. Instagram — required fix

## 5.1 Current failure to correct

Current `NativeInstagramEngine` can treat a JSON object as the target media merely because:

- `shortcode/code == targetShortcode`, or
- `id/pk == targetMediaId`.

That is insufficient.

A current Instagram payload can contain an outer/wrapper/metadata object carrying the same target identifiers while the actual media object is nested deeper.

Current bad flow:

```text
matching id/shortcode wrapper
        ↓
return wrapper as target media
        ↓
wrapper has no video_versions / carousel_media
        ↓
NoVideo
        ↓
TERMINATED at DESKTOP
```

This is a false terminal classification.

## 5.2 Mandatory media-node validation

Introduce a clear distinction between:

1. **target identifier match**, and
2. **actual media node**.

A matching object is an actual media candidate only when it contains media-defining structure such as one or more of:

- `video_versions`
- `video_url`
- `carousel_media`
- `image_versions2`
- a known media type field sufficient to classify the object
- a recognized nested public-product media container

If an object only matches `id/pk/code/shortcode` but does not contain media-defining structure:

> **Do not return it as the final target media object. Continue descending into its children.**

## 5.3 Handle current public-product nesting

Add explicit handling for public logged-out media shapes such as:

- `if_not_gated_logged_out`

Behavior:

```text
matching wrapper
  └─ if_not_gated_logged_out
       └─ actual media object
```

The nested object must still be validated against the target media id/shortcode.

Do not select unrelated/recommended media.

## 5.4 Parse `data-sjs` scripts

Do not assume all useful Instagram page state is inside:

```html
<script type="application/json">...</script>
```

Add a dedicated collector/parser for script elements containing `data-sjs`, independent of whether `type="application/json"` is present.

Requirements:

- support valid JSON object/array payloads;
- support nested JSON strings through bounded recursive decoding;
- do not use broad destructive global string replacement that can corrupt signed media URLs;
- preserve escaped URLs and query tokens correctly.

## 5.5 Profile escalation semantics

A wrapper match without validated media is **not `NoVideo`**.

For ambiguous / unsupported / wrapper-only payloads:

```text
DESKTOP
  ↓ technical/parser result
MOBILE
  ↓ technical/parser result
CRAWLER
  ↓ technical/parser result
yt-dlp technical fallback if allowed
```

Only return terminal `NoVideo` when an **actual validated target media node** is present and can be positively classified as having no video.

## 5.6 Crawler public-page path

Evaluate and implement the currently working public-page strategy used by maintained Android references:

- Googlebot/crawler identity;
- fetch `/p/<shortcode>/` as an alternate public product page even when the source URL is `/reel/<shortcode>/`, when this is the current working public-page behavior.

Preserve required source query parameters such as `stkn` on normal source fetches. For the crawler alternate URL, preserve query data where it remains syntactically valid and useful.

Do not blindly drop signed/share parameters.

## 5.7 Instagram tests required

Add deterministic tests for at least:

1. matching outer wrapper + nested `if_not_gated_logged_out` + video
   - expected: SUCCESS

2. matching wrapper without media fields, nested valid media deeper
   - expected: parser continues downward and succeeds

3. matching wrapper without media fields and no valid media anywhere
   - expected: technical/unsupported result
   - must **not** be terminal `NoVideo`

4. genuine validated image-only target media
   - expected: terminal `NoVideo`

5. `data-sjs` script without `type="application/json"`
   - expected: media extracted

6. `stkn` and literal `+` / escaped query tokens survive normalization

7. DESKTOP false-wrapper result → MOBILE/CRAWLER escalation
   - expected: later profile can succeed

Do not make unit tests call live Instagram.

---

# 6. X / Twitter — required fix

## 6.1 Current failure to correct

Current code effectively performs:

```text
GraphQL result.__typename == TweetUnavailable or TweetTombstone
        ↓
DeletedOrNotFound
        ↓
canFallback = false
        ↓
TERMINATED
```

For an anonymous web GraphQL call, this is too aggressive.

For the owner-confirmed playable regression case, this behavior is known wrong.

## 6.2 Provisional unavailable state

A single GraphQL response with:

- `TweetUnavailable`
- `TweetTombstone`

must be treated as a **provisional GraphQL/session result**, not final proof of deletion/private status.

Do not immediately emit terminal `DELETED_OR_NOT_FOUND`.

Introduce an internal technical/provisional classification or equivalent control flow that remains fallback-eligible.

Do not weaken the public user-facing error taxonomy globally; fix the X resolver decision point.

## 6.3 Required recovery sequence

On provisional unavailable:

```text
GraphQL attempt #1
  ↓ TweetUnavailable / TweetTombstone
clear cached bearer/guest state as appropriate
  ↓
refresh/bootstrap guest session
  ↓
GraphQL retry once
  ↓
if still provisional unavailable
  ↓
public HTML/page fallback
  ↓
parse target status media
```

Rules:

- bounded retry only; no loops;
- never log bearer/guest tokens;
- keep target status ID fixed;
- do not change to a different tweet;
- preserve current working transaction-id/header logic;
- preserve current working bitrate selection.

## 6.4 HTML/public-page fallback must actually run

Current terminal GraphQL classification prevents the HTML fallback from running.

Change this so the HTML/public-page fallback is attempted after provisional GraphQL unavailable.

If the existing `window.__INITIAL_STATE__` parser does not match the current public page, inspect the current response and add the **smallest necessary** parser extension.

Do not invent speculative parsers. Base any extension on sanitized observed payload shape and add an offline fixture.

## 6.5 Final terminal classification

For this task, do not return `DELETED_OR_NOT_FOUND` merely because GraphQL used an unavailable typename.

A terminal deleted/private/not-found result should require corroborating public-surface evidence, for example:

- explicit stable tombstone/deleted page semantics after independent public-page fetch; or
- another concrete response shape that unambiguously proves target content is not publicly present.

If GraphQL is unavailable and public-page parsing also cannot determine state, return a technical/parser/access error, not a fabricated deleted/private conclusion.

## 6.6 X tests required

Add deterministic tests for at least:

1. GraphQL attempt #1 = `TweetUnavailable`
   - refreshed GraphQL attempt #2 = valid video tweet
   - expected: SUCCESS

2. GraphQL attempts remain provisional unavailable
   - HTML fallback contains valid video
   - expected: SUCCESS

3. GraphQL provisional unavailable + HTML parser cannot resolve current layout
   - expected: fallback-eligible technical error
   - must not be terminal `DELETED_OR_NOT_FOUND`

4. confirmed explicit deleted/tombstone public-page fixture
   - expected: terminal deleted/not-found only when corroborated

5. existing passing video fixture remains PASS

6. existing photo-only/text-only behavior remains `NO_VIDEO` only when target tweet data is successfully resolved and genuinely contains no video

Do not call live X in CI.

---

# 7. Threads — required fix

## 7.1 Preserve what already works

The successful case:

`https://www.threads.com/share/BBk97kGup5/`

proves that the current design can:

- receive a `/share/` URL;
- resolve it;
- parse at least one current Threads page;
- return native media.

Do **not** rewrite the whole Threads resolver.

Do not replace working redirect/canonical behavior without evidence.

## 7.2 Current failure to correct

The failed share URLs already resolve far enough to produce target post codes:

- `BAVLndHzC` → `DdcLVjtknJ2`
- `_5-t0FGYG` → `DdaQOUWkpua`

The failure is therefore primarily:

> current page payload / nested JSON / target-media structure is not fully recognized.

Current `findPostByCode()` can also stop too early:

```text
object.code == targetCode
        ↓
return object immediately
```

As with Instagram, a matching wrapper is not necessarily the actual media node.

## 7.3 Add target media-node validation

Separate:

1. target post identifier match;
2. actual media-bearing post node.

When a matching `code` object lacks relevant media/post structure, keep descending.

Recognized relevant media structure includes, where actually observed:

- `video_versions`
- `video_dash_manifest`
- `carousel_media`
- `linked_inline_media`
- known target post media containers
- legitimate quoted attachment structure only when the target-post semantics require it

Do not grab unrelated recommended/related/suggested media.

## 7.4 Robust nested script decoding

Current generic escaped-script handling is too fragile if it relies on:

- first `{` / last `}`;
- global `\\"` replacement;
- global backslash replacement.

Implement a bounded nested JSON decoding approach.

Requirements:

- collect `application/json` scripts;
- collect relevant `data-sjs` scripts if present;
- inspect generic script payloads only when they contain the target code or known data marker;
- decode nested JSON strings recursively with a strict depth limit;
- preserve escaped CDN URLs and signed query strings;
- reject malformed payloads cleanly;
- do not parse arbitrary unrelated page text into a giant JSON substring.

## 7.5 Preserve target isolation

This requirement remains non-negotiable.

If target code is `DdcLVjtknJ2`, the resolver must not succeed by selecting:

- recommended posts;
- related posts;
- feed neighbors;
- unrelated quoted media;
- arbitrary first video in page HTML.

Success requires media associated with the target post semantics.

## 7.6 No false `NoVideo`

If a target-code wrapper is found but no validated actual media node is found:

- return technical/unsupported layout;
- continue profile escalation;
- allow configured technical fallback.

Only return terminal `NoVideo` after a validated target post/media object proves the target is text/image-only.

## 7.7 Do not change working download semantics

Preserve:

- progressive MP4 direct download;
- DASH video/audio parsing;
- best representation selection;
- separate audio handling;
- FFmpeg merge;
- merge failure = failure;
- current media request headers;
- current quality-option honesty.

Do not change these unless a new reproduced failure specifically requires it.

## 7.8 Threads tests required

Add deterministic sanitized fixtures/tests for at least:

1. currently passing share/canonical shape
   - must remain PASS

2. target-code wrapper with media nested deeper
   - must find actual target media

3. escaped/nested JSON string carrying target post media
   - must decode without corrupting CDN query tokens

4. target wrapper present but actual media absent
   - technical result, not `NoVideo`

5. unrelated recommendation contains a video while target does not
   - must not select recommendation

6. progressive media extraction

7. DASH video+audio extraction

8. existing merge-failure test remains valid

No live Threads calls in CI.

---

# 8. Shared parser rule for Meta platforms

Instagram and Threads now have the same class of bug risk:

> identifier match is not equivalent to media-node match.

Where practical, extract a **small shared parsing concept/helper** only if it reduces duplicated fragile logic without creating a new framework.

Do not perform a broad refactor merely for abstraction purity.

The priority is:

1. correctness;
2. target isolation;
3. regression tests;
4. minimal change surface.

---

# 9. Diagnostics required for the next APK

The next CI APK must provide enough debug trace to distinguish resolver stages.

Do not expose secrets or full signed media URLs.

### Instagram diagnostic example

```text
[Resolver] platform=INSTAGRAM
[Instagram] profile=DESKTOP target_id_match=true actual_media=false
[Instagram] profile=MOBILE ...
[Instagram] profile=CRAWLER alternate_path=/p/<shortcode> ...
[Instagram] final=SUCCESS|TECHNICAL|NO_VIDEO
```

### Threads diagnostic example

```text
[Resolver] platform=THREADS
[Threads] share_resolved=true
[Threads] target_code=DdcLVjtknJ2
[Threads] script_source=application_json|data_sjs|nested_json
[Threads] target_wrapper=true actual_media=true|false
[Threads] final=SUCCESS|PARSE_ERROR|NO_VIDEO
```

### X diagnostic example

```text
[Resolver] platform=X
[X] graphql_attempt=1 typename=TweetUnavailable
[X] guest_refresh=true
[X] graphql_attempt=2 result=...
[X] html_fallback=true
[X] final=SUCCESS|TECHNICAL|DELETED_OR_NOT_FOUND
```

Never log:

- bearer tokens;
- guest tokens;
- cookies;
- authorization headers;
- full signed CDN URLs;
- private account information.

---

# 10. Error-classification rules for this regression round

Use this decision table.

| Situation | Correct result |
|---|---|
| Actual validated target media contains playable video | SUCCESS |
| Target identifier wrapper found, actual media not resolved | technical / unsupported layout |
| GraphQL anonymous session says unavailable once | provisional technical state; retry/fallback |
| Actual validated target post is image/text only | terminal NO_VIDEO |
| Independent public evidence explicitly proves deleted/private | terminal deleted/private classification |
| Parser cannot understand current payload | technical / PARSE_ERROR |
| Network/session bootstrap fails | NETWORK / TOKEN/API technical error |

Do not convert uncertainty into a terminal content classification.

---

# 11. Test and CI gate

Before pushing the implementation, run:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Requirements:

- all existing tests pass;
- all new regression tests pass;
- lint has no new blocking issues;
- debug APK builds;
- GitHub Actions CI passes;
- CI artifact is uploaded.

CI success is **not** platform acceptance.

Do not mark Instagram / Threads / X PASS until the owner retests the APK on device.

---

# 12. Real-device acceptance gate

The new APK must be tested against this same corpus.

## Instagram

Owner must confirm for the supplied Reel cases:

- Analyze: PASS
- Resolver: NativeInstagramEngine
- No false `NoVideo`
- Download: PASS
- Saved file playable: PASS
- Audio present where source has audio: PASS

## Threads

### `BBk97kGup5`

- must remain PASS
- no regression

### `BAVLndHzC`

- must change from current PARSE_ERROR to PASS

### `_5-t0FGYG`

- must change from current PARSE_ERROR to PASS

For all successful Threads cases:

- resolver should be NativeThreadsEngine;
- target media must belong to target post;
- no recommendation-media false success;
- downloaded video must be playable;
- audio must be present when source has audio.

## X

### `2100527337762918655`

- must remain PASS

### `2100528665385959506`

- must change from current false unavailable/deleted classification to PASS

For the failed X case, the next trace must not terminate solely because GraphQL returned `TweetUnavailable` / `TweetTombstone`.

---

# 13. Stop conditions

Stop and report evidence instead of expanding scope if any of the following becomes true:

1. Current anonymous public web access for a supplied owner-confirmed playable post genuinely requires authenticated user credentials.
2. A platform requires a secret/private API credential that cannot be obtained through a legitimate public bootstrap flow.
3. A proposed fix requires cookies.txt import, WebView login, accessibility access, or credential capture.
4. Fixing the parser would require copying GPL source into this project.
5. A broad dependency/runtime upgrade is the only proposed path.
6. The real response no longer contains enough public information to implement the current native approach.
7. The only proposed “fix” is to move Instagram/Threads/X back to yt-dlp-first routing.

A stop report must contain:

- case ID / target URL or shortcode/status ID;
- HTTP status(es);
- sanitized response classification;
- which resolver/profile was attempted;
- which reference implementation was compared;
- exact blocker;
- smallest next option.

Do not label the post itself deleted/private/unavailable unless evidence supports that statement independently of a single failed resolver path.

---

# 14. Workflow — strict

Continue the existing work only:

```text
Repository: charleswoo1/video_downloader_android
PR: #2
Branch: feat/android-v0.1.0-initial
```

Required workflow:

1. Read both handoff contracts.
2. Read current PR #2 head before editing.
3. Re-read the current implementations of:
   - `NativeInstagramEngine.kt`
   - `NativeThreadsEngine.kt`
   - `NativeXEngine.kt`
   - `PlatformEngineRouter.kt`
   - `PlatformExtractionError.kt`
   - shared HTTP/session code
4. Re-check the reference registry.
5. Implement only the scoped fixes.
6. Add sanitized fixtures and deterministic tests.
7. Run local Gradle gates.
8. Push to the same branch.
9. Wait for CI.
10. Report artifact for owner real-device test.
11. Stop.

Do not:

- open another PR;
- merge;
- tag;
- release.

---

# 15. Definition of done

This regression fix is implementation-ready for owner test only when all are true:

- [ ] Instagram actual-media-node validation implemented.
- [ ] Instagram `if_not_gated_logged_out` handled.
- [ ] Instagram `data-sjs` payloads handled.
- [ ] Instagram false wrapper match no longer returns terminal `NoVideo`.
- [ ] Instagram profile escalation remains functional.
- [ ] X single GraphQL `TweetUnavailable/TweetTombstone` is no longer terminal by itself.
- [ ] X bounded session refresh / GraphQL retry implemented.
- [ ] X public HTML fallback runs after provisional unavailable.
- [ ] X false deleted/private classification regression covered.
- [ ] Threads existing working share case preserved.
- [ ] Threads matching wrapper no longer ends search prematurely.
- [ ] Threads nested/escaped payload handling strengthened.
- [ ] Threads target isolation preserved.
- [ ] Threads false wrapper does not return terminal `NoVideo`.
- [ ] New deterministic fixtures/tests added.
- [ ] Existing YouTube/Facebook baseline unchanged.
- [ ] Unit tests pass.
- [ ] Lint passes.
- [ ] assembleDebug passes.
- [ ] CI passes.
- [ ] Debug APK artifact uploaded.
- [ ] No merge.
- [ ] No tag.
- [ ] No release.
- [ ] Instagram / Threads / X remain marked `NEEDS OWNER DEVICE TEST` until owner confirms.

---

# 16. Required agent report — use this exact structure

```text
Contract read:
- docs/handoffs/HANDOFF_ANDROID_V0.1.0.md
- docs/handoffs/HANDOFF_ANDROID_V0.1.0_REAL_DEVICE_REGRESSION_FIX.md

PR:
Branch:
Starting head:
Final head:

Instagram:
- root cause fixed:
- parser changes:
- profile/fallback changes:
- new fixtures/tests:
- remaining limitation:

Threads:
- root cause fixed:
- share resolution changed? YES/NO
- parser changes:
- target-isolation changes:
- new fixtures/tests:
- remaining limitation:

X:
- root cause fixed:
- GraphQL unavailable handling:
- guest/session retry:
- HTML fallback:
- new fixtures/tests:
- remaining limitation:

Regression protection:
- YouTube:
- Facebook:
- existing Threads PASS case:
- existing X PASS case:

Tests:
- ./gradlew test:
- ./gradlew lintDebug:
- ./gradlew assembleDebug:

CI:
- run:
- conclusion:
- artifact:
- artifact digest:

Owner-device status:
- Instagram: NEEDS OWNER DEVICE TEST
- Threads BBk97kGup5: NEEDS OWNER DEVICE TEST
- Threads BAVLndHzC: NEEDS OWNER DEVICE TEST
- Threads _5-t0FGYG: NEEDS OWNER DEVICE TEST
- X 2100527337762918655: NEEDS OWNER DEVICE TEST
- X 2100528665385959506: NEEDS OWNER DEVICE TEST

Merged: NO
Tag created: NO
Release created: NO
```

Do not replace `NEEDS OWNER DEVICE TEST` with PASS based only on unit tests, emulator tests, or CI.
