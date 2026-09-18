# HANDOFF — Android Platform Native V2

## 0. Objective

Rework the Android downloader into a more stable local-only multi-engine architecture using actively maintained upstream extractor implementations as explicit engineering references.

This handoff replaces the strategy of repeatedly patching the existing native Meta parser without reference implementations.

Target platforms for new native primary engines:

- Instagram
- Threads
- X / Twitter

yt-dlp remains installed and remains the generic/fallback engine.

Cobalt/server backend is explicitly out of scope.

---

## 1. Repository and durable references

Repository:

`https://github.com/charleswoo1/video_downloader_android`

Before implementation, read:

- `AGENTS.md`
- `CONTRIBUTING.md`
- `docs/references/UPSTREAM_EXTRACTOR_REFERENCE_REGISTRY.md`
- `docs/regression/META_REAL_DEVICE_CASES.md`
- previous Native Meta handoff(s)
- current `PlatformEngineRouter`
- current `NativeInstagramEngine`
- current `NativeThreadsEngine`
- current yt-dlp runtime/plugin integration

The upstream-reference registry is mandatory. Do not implement platform extractors from memory or by guessing page structure.

---

## 2. Base and branch

Current validated app-code baseline before this planning update:

`0f003845d249ac939c5f82bb27b2cb2328bb8b7c`

Current documentation commit adding the upstream registry:

`d240ffa9e1375a3f4327a6231b407d90ca2e139a`

Source branch:

`spike/native-meta-extractors`

Create:

`spike/platform-native-v2`

from the latest owner-approved head of `spike/native-meta-extractors`.

Do not branch from `main`.

Do not merge, tag, or release during this handoff.

---

## 3. Baseline that must remain available

The existing modern yt-dlp runtime is known-good enough to remain as fallback:

- yt-dlp: `2026.08.30.232658`
- Python: `3.14.6`
- curl_cffi: `0.16.2`
- FFmpeg: `8.1.2`
- Android 16 KB page-size compatibility: verified
- bundled Threads plugin:
  - repo: `tribixbite/yt-dlp-threads`
  - pinned commit: `c4c44141cb10715f94296a808f5d89a0d24dfe94`

Do not remove or upgrade this runtime as part of Platform Native V2.

Do not re-enable startup yt-dlp auto-update.

---

## 4. Why V2 is required

Real-device testing shows the current architecture is useful but not stable enough:

- YouTube / Facebook generally work through yt-dlp.
- X can still report `No video could be found` for cases requiring further classification.
- Instagram succeeds on some public Reels but fails on others with:
  - empty media response;
  - audience/access restrictions;
  - native false `NoVideo` classifications.
- Threads succeeds on some public share URLs but fails on others because target data is absent from the specific page variant returned.

The next step is not another isolated selector/path patch.

V2 must implement maintained-reference-driven platform engines.

---

## 5. Primary upstream references

The exact pinned refs and license rules are in:

`docs/references/UPSTREAM_EXTRACTOR_REFERENCE_REGISTRY.md`

Primary implementation references:

### Instagram
`2Xsave/insave@6454affbd8e7960db012c81ffc1c213b75e8947b`
MIT

### Threads
`2Xsave/trsave@2841945254c24c1ad3f3658410788503bcdced72`
MIT

### X / Twitter
`2Xsave/twsave@0da4dd7db8a0f93445338821309cff736c79b9ec`
MIT

### Common HTTP/session
`2Xsave/2xsave_common@1d52f56aa214a3c4e3c965d480b52a08b1643281`
MIT

### Multi-engine routing reference
`2Xsave/2XsaveTUI@301b6992bb5aa44c8a374ef1125981c77692de56`
MIT

Research-only references include InstaDownload, YTDLnis, Seal and NewPipeExtractor. Respect GPL license boundaries in the registry.

---

## 6. Phase 0 — upstream delta audit

Before writing implementation code:

1. Check the Tier A upstream repos for commits newer than the pinned SHA.
2. If newer commits exist:
   - inspect the diffs relevant to extraction/session behavior;
   - do not blindly move to latest;
   - update the registry in a separate docs commit if a newer commit should become the implementation reference;
   - report why.
3. Read the specified `client.rs`, `metadata.rs`, `config.rs`, `constants.rs`, and routing files.
4. Produce a short implementation note in the PR describing:
   - which upstream mechanisms are being ported;
   - which are intentionally not being ported;
   - Android-specific adaptations.

No implementation should begin until this audit is complete.

---

## 7. V2 architecture

Target:

```text
PlatformEngineRouterV2
│
├─ NativeInstagramEngineV2
├─ NativeThreadsEngineV2
├─ NativeXEngine
└─ YtDlpEngine
   ├─ YouTube
   ├─ Facebook
   ├─ TikTok
   ├─ Generic
   └─ technical fallback for Instagram / Threads / X
```

A migration-compatible name is acceptable; do not duplicate routers permanently.

Native engines are primary for Instagram, Threads, and X.

yt-dlp is a technical fallback, not a second unconditional attempt.

---

## 8. Shared HTTP/session layer

Replace the ad-hoc Meta-only request behavior with a platform-neutral native HTTP/session layer.

Suggested components:

- `PlatformHttpSession`
- `BrowserIdentity`
- `RequestProfile`
- `RetryPolicy`
- `PlatformCookieJar`

Use OkHttp/Kotlin.

### Required request profiles

At minimum:

- DESKTOP_NAVIGATION
- MOBILE_NAVIGATION
- CRAWLER_NAVIGATION
- API
- MEDIA

Each profile should produce coherent headers. Do not only swap the User-Agent while leaving contradictory client hints/fetch headers.

### Determinism

Do not randomly rotate user agents on every request merely because an upstream CLI does so.

For an Android app, use a deterministic escalation sequence:

1. vetted desktop identity
2. vetted mobile identity
3. crawler profile only when the platform/use-case requires it

Transport retry jitter may be used for backoff.

This makes A/B device results reproducible.

### Cookie behavior

Use an app-private cookie jar for anonymous/guest cookies returned by platforms.

Do not implement account login in this handoff.

Do not import browser cookies in this handoff.

Do not log cookie contents.

---

## 9. Shared error taxonomy

Introduce or refine structured errors to at least represent:

### Content / terminal
- NO_VIDEO
- PRIVATE_CONTENT
- LOGIN_REQUIRED
- AUDIENCE_RESTRICTED
- AGE_RESTRICTED
- DELETED_OR_NOT_FOUND
- GEO_RESTRICTED

### Technical / fallback-eligible
- NETWORK
- PAGE_VARIANT_UNSUPPORTED
- TARGET_NOT_IN_PAGE_DATA
- PARSE_ERROR
- API_ERROR
- TOKEN_REFRESH_FAILED
- MEDIA_URL_UNSUPPORTED
- TRANSIENT_HTTP_ERROR

### Infrastructure
- FFMPEG_ERROR
- STORAGE_ERROR
- CANCELLED

Router behavior must use this taxonomy.

Do not decide fallback based on raw English error-string matching if a structured error is available.

---

## 10. Fallback policy

### Instagram

```text
NativeInstagramEngineV2
  ├─ success → use native
  ├─ technical error → yt-dlp fallback once
  └─ content/access terminal → stop
```

### Threads

```text
NativeThreadsEngineV2
  ├─ success → use native
  ├─ technical error → yt-dlp + Threads plugin fallback once
  └─ content/access terminal → stop
```

### X

```text
NativeXEngine
  ├─ success → use native
  ├─ technical/API/token error → yt-dlp fallback once
  ├─ explicit NO_VIDEO → stop
  └─ content/access terminal → stop
```

No fallback loops.

No Native → yt-dlp → Native retry.

---

## 11. Native Instagram V2

Port the stable concepts from `2Xsave/insave` into Kotlin.

Do not embed Rust.

### URL handling

Support:
- `/reel/<shortcode>`
- `/p/<shortcode>`
- `/tv/<shortcode>` when still encountered
- common share/query wrappers

Preserve original content type/canonical form rather than always converting to `/reel/`.

### Request escalation

Suggested anonymous sequence:

1. desktop navigation
2. mobile navigation on technical/page-variant failure
3. crawler navigation only where evidence supports it
4. yt-dlp fallback after native technical failure

Do not retry for explicit audience/login restrictions.

### Parsing

Scan all relevant script payloads, including:
- `<script type="application/json">`
- data-sjs/server-rendered payloads where applicable

Support newer structures such as:
- `xdt_api__v1__media__shortcode__web_info`
- `items[0]`
- nested `data.media`
- `video_versions`
- `carousel_media`
- `image_versions2`

### Target isolation

Resolve the target media by shortcode/media identity.

If converting shortcode to numeric media ID is used, implement and test it explicitly.

Never return the first unrelated video found on the page.

### CDN URL normalization

Handle:
- escaped slashes;
- unicode escapes;
- percent-encoded URL components.

Do not require `.mp4` suffix.

Recognize valid Instagram/Meta CDN video paths such as CDN-hosted and `o1/v/t2/f2/m...` forms when the parsed field semantically represents a video rendition.

### Output

Return normalized renditions:
- url
- width/height if known
- bitrate/priority if known
- media type
- thumbnail

Do not fabricate resolution options.

---

## 12. Native Threads V2

Port the stable concepts from `2Xsave/trsave` into Kotlin while preserving the strict target-isolation behavior already learned from `yt-dlp-threads`.

### URL support

Support:
- `threads.com`
- `threads.net`
- `/@user/post/<code>`
- `/share/<code>/`
- `/t/<code>/` if encountered

### Share/canonical resolution

Resolve the actual target shortcode.

Do not assume the share code equals the post shortcode.

### Request/session behavior

Use shared desktop/mobile browser identities and app-private cookies.

Use deterministic retry/escalation.

### Parsing

Scan every relevant application/json/data-sjs payload.

Support recursive structures containing:
- `video_versions`
- `video_dash_manifest`
- `image_versions2`
- `carousel_media`
- quoted/reshared wrappers
- linked inline media

### Target isolation

The selected media must belong to the intended target post/code.

Mandatory negative fixture:
HTML contains unrelated video(s), target code is absent → return `TARGET_NOT_IN_PAGE_DATA`, not success.

### Meta CDN URLs

Normalize escaped/encoded URLs.

Do not require `.mp4` suffix.

### Direct media download

Use MEDIA request profile rather than navigation headers.

---

## 13. Native X / Twitter engine

Add `NativeXEngine` using `2Xsave/twsave` as primary MIT reference.

This is a new engine.

### URL parsing

Support:
- `x.com/<user>/status/<id>`
- `twitter.com/<user>/status/<id>`
- common query parameters

Extract numeric tweet/status id.

### Session

Maintain anonymous X cookie state.

### Bearer token strategy

Do not permanently rely on a single hard-coded bearer token.

Implement layered discovery modeled after the reference:

1. inspect X HTML where possible;
2. inspect a limited number of current `abs.twimg.com/responsive-web/client-web/*.js` assets;
3. use a known fallback bearer token only as last resort, with clear diagnostics.

Never display/log bearer tokens.

### Guest token

Use existing `gt` cookie if present.

Otherwise obtain guest token through the public web-client flow.

Handle:
- cookie-provided token;
- `x-guest-token` response header;
- refresh path.

Never treat guest token as user authentication.

### GraphQL

Use current public web GraphQL operation/endpoint shape from the pinned reference after auditing it.

Build:
- variables;
- feature flags;
- field toggles;
- Authorization;
- x-guest-token;
- required web-client headers.

### Refresh behavior

On 401/403:
- refresh bearer and/or guest token in a bounded way;
- retry once per refresh path;
- no infinite loop.

### Parsing

Parse:
- `tweetResult.result`
- `legacy`
- `extended_entities.media`
- video / animated_gif
- `video_info.variants`

Filter actual video MIME types and select the best available bitrate for Best quality.

Expose lower variants if resolution/bitrate mapping is trustworthy.

### HTML fallback

If GraphQL fails technically:
- fetch the post page;
- inspect `window.__INITIAL_STATE__` or current equivalent if present;
- use as native fallback before yt-dlp.

### NO_VIDEO

If X data clearly shows the target post has no video, return terminal `NO_VIDEO`.

Do not call yt-dlp just because the user supplied an X URL.

This is important for distinguishing a genuine no-video post from extractor failure.

---

## 14. Engine trace / debug instrumentation

This is mandatory in V2.

The previous A/B tests were hard to interpret because the UI did not clearly show which engine produced the final result.

Add an operation-scoped `EngineTrace`.

At minimum record:

```text
operation_id
platform
primary_engine
primary_result_category
request_profile_sequence
fallback_attempted
fallback_engine
fallback_result_category
final_engine
final_result
```

Debug build UI should update after every analysis/download and show a compact line such as:

`Engine: Native Threads (desktop→mobile) · fallback: no`

or:

`Engine: yt-dlp fallback · Native IG: PAGE_VARIANT_UNSUPPORTED`

Do not expose:
- tokens
- cookies
- local paths
- raw HTML

Do not reuse a startup-only diagnostics snapshot.

---

## 15. Keep yt-dlp responsibilities narrow

After V2:

Primary yt-dlp platforms:
- YouTube
- Facebook
- TikTok
- generic supported URLs

Fallback yt-dlp platforms:
- Instagram
- Threads
- X

Do not rewrite YouTube in this handoff.

Do not replace yt-dlp with NewPipe in this handoff.

NewPipeExtractor remains architectural/error-model research only.

---

## 16. Future authentication boundary — design only

Do not implement login UI yet.

However, avoid architecture that makes authenticated sessions impossible later.

A small interface boundary is acceptable, for example:

```kotlin
interface PlatformSessionProvider {
    fun cookiesFor(platform: Platform): List<Cookie>
    fun hasAuthenticatedSession(platform: Platform): Boolean
}
```

Current implementation can be anonymous-only.

Future owner-approved work may use a WebView-based session model inspired by active Android projects such as YTDLnis/Seal.

No credentials in V2.

---

## 17. Licensing

### MIT sources

If implementation is ported from:
- 2Xsave/insave
- 2Xsave/trsave
- 2Xsave/twsave
- 2Xsave/2xsave_common
- 2Xsave/2XsaveTUI

update `THIRD_PARTY_NOTICES.md` with:
- repository
- pinned commit
- MIT attribution
- files/algorithms materially ported

Prefer documenting source lineage at file/class level when substantial logic is ported.

### GPL references

Do not copy or line-for-line translate:
- InstaDownload
- YTDLnis
- Seal
- NewPipeExtractor

They are research-only under the current project licensing plan.

---

## 18. Tests

### Shared HTTP/session

Test:
- profile headers are coherent;
- deterministic profile escalation;
- cookies persist only in app-private in-memory/private store;
- bounded retry;
- 401/403 refresh is bounded;
- tokens are redacted from diagnostics.

### Instagram fixtures

At minimum:
- `xdt_api__v1__media__shortcode__web_info.items[0]`
- nested `data.media`
- single video_versions
- carousel video
- escaped/percent-encoded CDN URL
- valid CDN video without `.mp4`
- target absent but unrelated video present
- explicit audience restriction shell
- malformed JSON

### Threads fixtures

At minimum:
- canonical single video
- share resolved to canonical
- target code absent with unrelated video present
- progressive video_versions
- DASH
- carousel
- quoted_attachment_post
- linked_inline_media
- escaped/percent-encoded CDN URL
- CDN video without `.mp4`
- malformed JSON

### X fixtures

At minimum:
- GraphQL video tweet
- animated GIF
- multiple bitrate variants
- photo-only tweet → NO_VIDEO
- text-only tweet → NO_VIDEO
- GraphQL 401 then successful refresh
- GraphQL technical failure + HTML fallback
- malformed result
- restricted/not-found classification

### Router tests

For IG/Threads/X:
- native success → no fallback
- technical native failure → yt-dlp exactly once
- terminal restriction → no fallback
- NO_VIDEO → no fallback
- fallback success reports final engine accurately
- fallback failure preserves both categories in debug trace

---

## 19. Real-device regression corpus

Retain:

`docs/regression/META_REAL_DEVICE_CASES.md`

Add X cases under a new section.

Current observed X case:

`https://x.com/SmallQQQQQ/status/2099089385958645960`

Observed yt-dlp result:

`No video could be found in this tweet`

Do not classify this as a bug until Native X inspects actual target metadata.

Record final result as one of:
- genuine NO_VIDEO;
- Native X success;
- restricted;
- technical extraction failure.

Also add at least one owner-confirmed X video PASS URL during device testing.

Live URLs must not be mandatory CI dependencies.

---

## 20. Real-device A/B acceptance

After CI is green, owner tests:

### Instagram
- prior yt-dlp PASS case
- prior empty-media-response case
- prior audience-restricted case
- at least two fresh public Reels

### Threads
- prior PASS share URLs
- prior TARGET_NOT_IN_PAGE_DATA URLs
- at least two fresh public share URLs

### X
- known real video tweet
- the `2099089385958645960` classification case
- another fresh public video tweet

### Regression
- YouTube one video
- Facebook one video

For successful native media:
- metadata/thumbnail
- Best quality
- one explicit quality where available
- audio-only
- saved media playback
- correct audio/video
- cancellation where practical

---

## 21. Success criteria

Platform Native V2 succeeds when:

1. IG, Threads, and X have native primary engines.
2. Native engines use maintained-reference-driven request/session/parser behavior.
3. Known technical failures improve or are precisely classified.
4. Terminal restrictions no longer trigger pointless fallback chains.
5. Engine trace makes every A/B result attributable.
6. YouTube/Facebook do not regress.
7. CI remains deterministic and offline for parser fixtures.
8. No server backend is introduced.
9. No GPL source is copied.
10. Modern yt-dlp runtime remains available as fallback.

100% URL success is not required.

Correct classification is preferred over false-positive downloads.

---

## 22. Stop conditions

Stop and report rather than expanding scope if:

1. Instagram public extraction now universally requires authenticated account cookies.
2. Threads public extraction now universally requires authenticated account cookies.
3. X public GraphQL cannot be used anonymously without a user account.
4. Required implementation would copy GPL/AGPL code.
5. A native engine cannot guarantee target-media isolation.
6. A proposed fix requires a remote server/backend.
7. A proposed fix requires changing the validated Python/yt-dlp/FFmpeg runtime.
8. A proposed fix requires Accessibility, root, or another app's private data.
9. Token/cookie/session handling cannot be implemented without exposing sensitive state.
10. The change requires a large unrelated UI rewrite.

---

## 23. CI

Mandatory:

```bash
./gradlew test lintDebug assembleDebug
```

No mandatory live social-site tests in CI.

Upload Debug APK artifact.

Do not release.

Do not tag.

---

## 24. Git workflow

1. Sync `spike/native-meta-extractors`.
2. Read the reference registry.
3. Perform Phase 0 upstream delta audit.
4. Create `spike/platform-native-v2`.
5. Implement in reviewable commits, preferably:
   - shared HTTP/session layer;
   - Instagram V2;
   - Threads V2;
   - X engine;
   - router/trace;
   - fixtures/tests/docs.
6. Push.
7. Let CI complete.
8. Stop for review.

Do not merge.
Do not tag.
Do not release.

---

## 25. Final implementation report

Return:

```text
Branch:
Base commit:
Head commit:
CI run:
Artifact:

Upstream audit:
insave ref:
trsave ref:
twsave ref:
2xsave_common ref:
newer upstream changes adopted:

Shared HTTP/session:
request profiles:
cookie behavior:
retry policy:

Native Instagram V2:
implemented reference mechanisms:
known limits:

Native Threads V2:
implemented reference mechanisms:
known limits:

Native X:
bearer strategy:
guest-token strategy:
GraphQL strategy:
HTML fallback:
known limits:

Router:
native-primary platforms:
fallback rules:

EngineTrace:
debug UI status:

Licensing:
MIT ports documented:
GPL code copied: MUST BE NO

Tests:
test:
lintDebug:
assembleDebug:

Stop conditions:
Real-device tests required:
```

Then stop and wait for review.
