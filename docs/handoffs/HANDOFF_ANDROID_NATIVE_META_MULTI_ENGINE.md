# HANDOFF — Android Native Meta Multi-Engine Spike

## 0. Purpose

Build the next Android architecture spike for `charleswoo1/video_downloader_android`:

- add a native Instagram extraction engine;
- add a native Threads extraction engine;
- add a platform engine router;
- preserve the current modern yt-dlp runtime as the generic engine and fallback;
- compare native Meta extraction against the current yt-dlp baseline using real-device regression cases.

This is an **experimental architecture spike**, not a release task.

**Cobalt/server backend is explicitly out of scope.**

---

## 1. Repository and base

Repository:

`https://github.com/charleswoo1/video_downloader_android`

Base branch:

`spike/threads-plugin-integration`

Verified baseline before this handoff:

- branch head implementation: `c81a337067c9e4438277d70f5c07b9e1f790de2c`
- Android CI run #8 / run id `35304144597`: PASS

When executing this handoff, first fetch the current branch and confirm no newer owner-approved commits supersede this baseline.

Create a new branch from the current `spike/threads-plugin-integration` head:

`spike/native-meta-extractors`

Do not branch from `main`.

Do not merge `main`, PR #2, or any spike into another branch as part of this task unless explicitly requested by the owner.

---

## 2. Existing baseline that must be preserved

The current modern runtime has already been verified on a real Android device.

Known runtime:

- yt-dlp: `2026.08.30.232658`
- Python: `3.14.6`
- curl_cffi: `0.16.2` available
- FFmpeg: `8.1.2`
- 16 KB page-size: compatible
- Threads plugin: `tribixbite/yt-dlp-threads`
- Threads plugin pinned commit: `c4c44141cb10715f94296a808f5d89a0d24dfe94`

Known platform baseline:

- YouTube: PASS
- X: PASS
- Facebook: PASS
- Instagram: partial PASS
- Threads plugin: partial PASS

Do **not** change, upgrade, downgrade, or remove the modern runtime in this spike unless a compile blocker makes execution impossible. If that occurs, stop and report instead of silently changing the runtime.

Do not re-enable automatic yt-dlp runtime updates.

---

## 3. Target architecture

The target is a local-only multi-engine architecture:

```text
PlatformEngineRouter
├─ NativeInstagramEngine
├─ NativeThreadsEngine
└─ YtDlpEngine
   ├─ YouTube
   ├─ X
   ├─ Facebook
   ├─ TikTok
   ├─ Generic
   └─ fallback for Instagram / Threads
```

No remote backend.

No Cobalt API.

No server dependency.

No WebView login in this spike.

No cookies import in this spike.

---

## 4. Required engine abstraction

Introduce a platform-specific engine abstraction without pushing routing logic into Compose or `MainViewModel`.

A reasonable shape is:

```kotlin
interface PlatformMediaEngine {
    fun supports(platform: Platform): Boolean
    suspend fun extractMediaInfo(url: String): Result<MediaInfo>
    suspend fun download(
        request: DownloadRequest,
        destDir: File,
        onProgress: (Float, Long?, String?) -> Unit,
        onStatus: (String) -> Unit
    ): Result<File>
}
```

Exact naming may differ if a cleaner design fits the current codebase.

Required concrete responsibilities:

- `NativeInstagramEngine`
- `NativeThreadsEngine`
- existing modern yt-dlp implementation adapted as `YtDlpEngine` or wrapped behind the new router
- `PlatformEngineRouter`

The router must own fallback policy.

Do not implement platform branching in Compose UI.

Do not duplicate download-service lifecycle logic.

---

## 5. Fallback policy must be explicit

Define structured extraction failure categories.

At minimum distinguish:

### Technical failures — MAY fall back

Examples:

- HTML schema changed
- expected JSON block missing
- parse exception
- native extractor implementation error
- unsupported native media variant
- transient HTTP/network failure where retry/fallback is reasonable

### Content/access restrictions — MUST NOT blindly fall back

Examples:

- private content
- audience restricted
- age restricted
- login required
- deleted/not found
- explicit platform access denial
- no video in post

Do not make multiple engines repeatedly hit the same URL when the first engine already established that the content is restricted.

A recommended sealed result/error model is acceptable.

---

## 6. Native Meta HTTP layer

Create a reusable local HTTP layer for Meta extraction, for example:

`MetaWebClient`

Responsibilities:

- OkHttp-based HTTP requests;
- deterministic timeouts;
- redirect handling;
- request profiles/headers;
- response-body size sanity limits;
- HTML retrieval;
- sanitized diagnostics;
- no persistent credentials in this spike.

Do not give Instagram and Threads completely separate duplicate HTTP stacks unless required.

Keep request profiles explicit, e.g.:

- normal browser-like profile;
- crawler/link-preview profile where needed.

Do not spoof or store user credentials.

---

## 7. Native Instagram extractor scope

Implement only anonymous/public-content extraction.

### In scope

- public Reel
- public video post
- carousel containing video
- title/uploader when available
- thumbnail
- progressive video media URL
- enough metadata to populate current `MediaInfo`
- direct media download using the app's existing storage/service pipeline

### Out of scope

- username/password login
- WebView login
- browser cookie extraction
- cookies.txt import
- private account access
- Stories
- photo-only downloader feature
- profile image download
- DRM bypass

### Extraction direction

Use an independently implemented Android/Kotlin parser that can:

```text
Instagram URL
→ extract shortcode
→ request public post page
→ locate server-rendered JSON/data-sjs payload
→ identify the requested media object precisely
→ extract video_versions / carousel_media / image thumbnail
→ return direct CDN media information
```

Do not assume the first video object on the page belongs to the target post.

When needed, derive/compare the target media identifier from the shortcode, but implement independently and test it.

---

## 8. Instagram reference-license boundary

Research reference:

`Orang-Studio/InstaDownload`

Its current license is GPL-3.0.

Therefore:

- it may be used to understand observable behavior, data shapes, request strategy, and test ideas;
- **do not copy source code or near-verbatim implementation into this repository**;
- do not vendor GPL source;
- do not translate GPL code line-for-line into Kotlin in this project.

Use clean-room independent implementation based on public web responses and independently written tests.

If implementation requires copying GPL-covered source, stop and report licensing implications.

---

## 9. Native Threads extractor scope

Implement anonymous/public video extraction.

### In scope

- `threads.com`
- `threads.net`
- canonical `/@user/post/<shortcode>`
- `/share/<code>/`
- `/t/<code>` if encountered
- progressive video
- DASH metadata where practical
- carousel video
- quoted/reshared media variants
- title/uploader/thumbnail
- target-post isolation

### Out of scope

- authenticated Threads session
- private content bypass
- image-only downloader feature
- Cobalt server/API
- generic page “first video” fallback

### Required behavior

```text
Threads URL
→ normalize domain
→ if share URL, determine target canonical post/shortcode
→ fetch appropriate page/profile
→ parse data-sjs/server-rendered JSON
→ identify post.code == target shortcode
→ extract target post media only
→ video_versions / video_dash_manifest / carousel / supported wrapped-media cases
```

Never silently use an unrelated recommendation, reply, parent post, or quoted post unless the target post itself explicitly wraps that media.

---

## 10. Threads references and license boundaries

Reference 1:

`tribixbite/yt-dlp-threads`

Pinned baseline commit:

`c4c44141cb10715f94296a808f5d89a0d24dfe94`

License:

Unlicense / public domain.

This may be used as a direct algorithmic reference.

Reference 2:

Cobalt Threads PR #1558.

Cobalt is AGPL-3.0.

Therefore:

- study request behavior, observable page structures, edge cases and test categories only;
- **do not copy Cobalt source or translate it line-for-line**;
- do not vendor Cobalt code;
- do not add Cobalt runtime/server/API dependency.

The current Kotlin `ThreadsResolver` may also be used as local reference code because it is already part of this repository.

---

## 11. Preserve yt-dlp as fallback

Do not delete:

- `YtDlpPluginManager`
- bundled Threads plugin
- modern runtime preparation
- yt-dlp Threads support
- existing Kotlin `ThreadsResolver`

during this spike.

The experiment needs an A/B baseline.

Expected routing goal:

```text
Instagram
→ NativeInstagramEngine
→ technical failure only → yt-dlp

Threads
→ NativeThreadsEngine
→ technical failure only → yt-dlp + Threads plugin
→ only if plugin infrastructure itself is unavailable may existing Kotlin resolver remain a last-resort infrastructure fallback
```

Access/content restriction errors should terminate with a clear user-facing result rather than cycling through every engine.

---

## 12. Download path

Native extractors should return normalized media information rather than build their own separate app lifecycle.

Prefer a normalized internal structure that can represent:

- direct progressive URL;
- separate video/audio URL pair if needed;
- thumbnail;
- dimensions;
- media type;
- title/uploader;
- optional duration.

Reuse the existing:

- foreground service;
- cancellation;
- progress;
- MediaStore/Downloads save;
- filename collision behavior;
- notification cleanup.

Do not fork a second unrelated download-service architecture.

---

## 13. Quality options

For native Meta progressive media:

- expose only quality options supported by the actual media response;
- do not fabricate 1080p/720p options;
- “Best quality” may point to the highest-confidence/best rendition;
- audio-only is only shown if extraction/transcoding is supported by the current FFmpeg path.

For yt-dlp fallback, preserve existing format-selection behavior.

---

## 14. Error UX

The current UI sometimes displays an entire yt-dlp English exception.

Fix the presentation layer while implementing the router.

User-facing errors should be concise zh-TW.

Examples:

### Instagram technical extraction failure

`Instagram 暫時無法解析此貼文。平台可能未提供匿名媒體資料，或頁面格式已變更。`

### Instagram audience restriction

`此 Instagram 內容限制部分使用者觀看，匿名模式無法存取。`

### Threads target missing from page data

`Threads 已找到貼文連結，但目前頁面未提供可解析的目標媒體資料。`

Debug logs may retain sanitized detail.

Never display:

- full Python traceback;
- private filesystem path;
- cookies;
- session/token data;
- huge raw HTML/error dumps.

---

## 15. Real-device regression corpus

Add:

`docs/regression/META_REAL_DEVICE_CASES.md`

Record these owner-supplied real-device cases.

Do not automatically hit them in normal CI.

### Threads

#### TH-SHARE-FAIL-PAGEDATA-01

Input:

`https://www.threads.com/share/_2DcaFS7L/`

Observed canonical shortcode:

`DdZEpoeGbro`

Current yt-dlp Threads plugin result:

target post not found in page data.

Expected native-engine experiment:

determine whether the public page exposes target media using the native request/parser path.

#### TH-SHARE-PASS-01

Input:

`https://www.threads.com/share/BAPaySXLil/`

Current yt-dlp Threads plugin:

PASS metadata.

Use as non-regression case.

#### TH-SHARE-FAIL-PAGEDATA-02

Input:

`https://www.threads.com/share/BAYRqEnpRR/`

Observed canonical shortcode:

`DdY8E2dABUv`

Current yt-dlp Threads plugin:

target post not found in page data.

#### TH-SHARE-PASS-02

Input:

`https://www.threads.com/share/_6syxcd_8/`

Current yt-dlp Threads plugin:

PASS metadata.

### Instagram

#### IG-EMPTY-MEDIA-01

Input shortcode:

`DdXfLoyTs__`

Observed yt-dlp result:

`Instagram sent an empty media response`

Expected native-engine experiment:

determine whether anonymous server-rendered page data contains usable media.

#### IG-PASS-01

Input shortcode:

`DdS5sMrxkBq`

Current yt-dlp:

PASS metadata.

Use as non-regression case.

#### IG-AUDIENCE-01

Input shortcode:

`DbtoOl8zwMO`

Observed result:

`This content isn't available to everyone. It can't be seen by certain audiences.`

Classify as access/content restriction.

Do not treat a native failure here as evidence that the native extractor is broken.

---

## 16. Regression corpus privacy and stability

The regression document is engineering test data, not product UI.

Do not copy captions, usernames, thumbnails, or unrelated personal details into the regression document unless necessary.

URLs/shortcodes and technical result classifications are enough.

These are live third-party posts and may later be removed or changed.

Therefore:

- do not make CI success depend on them;
- mark live-device results with date;
- use saved synthetic/offline fixtures for parser unit tests.

---

## 17. Offline fixtures and parser tests

Create sanitized fixture-based tests for both native extractors.

Fixtures should cover structural cases, not contain unnecessary personal content.

Instagram fixture categories:

- single public video
- carousel with video
- no target media
- audience/login-gated shell
- malformed JSON/schema change

Threads fixture categories:

- target post with video_versions
- target post with DASH
- share/canonical resolution metadata
- carousel
- quoted attachment/linked inline media
- target shortcode absent while unrelated videos exist
- malformed JSON/schema change

The “target absent while unrelated video exists” test is mandatory.

It must prove the extractor refuses to download an unrelated clip.

---

## 18. A/B diagnostics

For debug builds, expose which engine handled each request.

At minimum log:

```text
Platform:
Primary engine:
Primary result category:
Fallback attempted: yes/no
Fallback engine:
Final engine:
```

Do not expose sensitive local paths.

A small debug-only diagnostics line/card is acceptable.

Production-facing UI should not require the user to understand engine internals.

---

## 19. Success metrics for this spike

The goal is not “100% of all Instagram/Threads URLs”.

The spike succeeds if:

1. native Meta engines work for known PASS cases;
2. at least one known yt-dlp technical-failure case is recovered by a native engine **or** the experiment produces clear evidence that it cannot be recovered anonymously;
3. content/access restrictions are classified separately from technical parser failures;
4. YouTube/X/Facebook behavior does not regress;
5. router fallback is deterministic and does not loop;
6. CI remains green;
7. no server dependency is introduced.

---

## 20. Required A/B result table

After implementation, report real-device results in this shape:

| Case | Native | yt-dlp | Final route | Notes |
|---|---|---|---|---|
| TH-SHARE-PASS-01 | | PASS | | |
| TH-SHARE-PASS-02 | | PASS | | |
| TH-SHARE-FAIL-PAGEDATA-01 | | FAIL | | |
| TH-SHARE-FAIL-PAGEDATA-02 | | FAIL | | |
| IG-PASS-01 | | PASS | | |
| IG-EMPTY-MEDIA-01 | | FAIL | | |
| IG-AUDIENCE-01 | expected restriction | restriction | no fallback loop | |

Owner will perform live-device verification after CI artifact is available.

---

## 21. Existing behaviors that must not regress

Preserve:

- Android Share Intent;
- manual URL input;
- API 36 baseline;
- foreground-service timeout handling;
- notification cleanup;
- deterministic MediaStore collision naming;
- single active download;
- cancel/restart race protection;
- stale terminal state clearing;
- Downloads/SocialVideoDownloader storage;
- current yt-dlp error-warning separation;
- modern runtime diagnostics;
- 16 KB compatibility.

---

## 22. CI

Run:

```bash
./gradlew test lintDebug assembleDebug
```

All must pass.

Continue producing a debug APK artifact.

Do not create a release.

Do not create a tag.

Do not alter release signing.

No live Instagram/Threads network tests in mandatory CI.

---

## 23. Stop conditions

Stop implementation expansion and report if any of the following becomes true:

1. Native Instagram requires authenticated cookies for even the known public PASS fixture/case.
2. Native Threads requires authenticated cookies for the known public PASS cases.
3. The only practical implementation requires copying GPL-3.0 or AGPL-3.0 source.
4. The router requires a major rewrite of foreground-service/storage architecture.
5. Native extraction introduces a significant security/privacy regression.
6. A platform response cannot be parsed safely without selecting unrelated media.
7. A fix requires changing the already validated modern yt-dlp runtime.
8. A server/backend becomes necessary.

Do not silently expand scope.

---

## 24. Git workflow

Create:

`spike/native-meta-extractors`

from the current owner-approved head of:

`spike/threads-plugin-integration`

Implement and commit there.

Push branch.

Wait for CI.

Open or update an appropriate Draft PR for review if the current repo workflow requires it.

Do not merge.

Do not tag.

Do not release.

---

## 25. Final implementation report

Return:

```text
Branch:
Commit:
CI run:
Artifact:

Base commit:

NativeInstagramEngine:
status:
supported cases:
known limits:

NativeThreadsEngine:
status:
supported cases:
known limits:

PlatformEngineRouter:
fallback policy:

Modern yt-dlp runtime unchanged:
yes/no

YouTube regression:
X regression:
Facebook regression:

A/B results:
[table]

Error taxonomy:
technical fallback categories:
content restriction categories:

Licensing:
GPL/AGPL code copied:
must be NO
yt-dlp-threads reference:
InstaDownload reference:
Cobalt PR reference:

Tests:
test:
lintDebug:
assembleDebug:

Stop conditions encountered:
Known limitations:
Owner real-device tests required:
```

Then stop and wait for review.

Do not merge.
Do not tag.
Do not release.
