# HANDOFF — Android v0.1.0 Platform Recovery / Multi-Resolver Implementation

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Status: **implementation contract / recovery task / NOT release authorization**

This document supersedes the earlier assumption that platform detection should simply route every supported social URL into yt-dlp.  
For Instagram, Threads, and X/Twitter, that assumption is now explicitly rejected by owner real-device evidence.

---

## 0. Mission

Make the Android v0.1.0 application perform the basic public-media workflow reliably on real devices:

```text
Android Share / pasted URL
        ↓
URL normalization + platform detection
        ↓
platform-specific resolver (when required)
        ↓
app-owned ResolvedMedia / MediaInfo
        ↓
download direct media streams or yt-dlp fallback
        ↓
FFmpeg merge / audio extraction when required
        ↓
MediaStore → Downloads/SocialVideoDownloader/
```

The immediate regression targets are:

- Instagram public Reel
- Threads public video post, including `threads.com/share/...`
- X/Twitter public video post

The task is **not** to keep patching yt-dlp error strings.  
The task is to implement the correct Android-side extraction architecture using already available open-source implementations as reference.

---

## 1. Non-negotiable direction

### 1.1 Android-first implementation

This repository is Android-only.

The Windows repository `charleswoo1/video_downloader` may be used only for:

- expected product behavior;
- known URL normalization behavior;
- expected output semantics;
- comparison of a resolver's conceptual behavior.

**DO NOT use the Windows repository as the primary implementation source for Android platform extraction.**

The implementation source of truth for this task must be:

1. current Android code in this repository;
2. relevant Android / portable open-source extractor implementations;
3. current behavior of the target public websites;
4. real-device evidence.

### 1.2 yt-dlp is NOT the primary resolver for these three platforms

For v0.1.0 recovery:

```text
Instagram  → platform-specific resolver FIRST → yt-dlp optional fallback
Threads    → platform-specific resolver FIRST → yt-dlp is NOT sufficient by itself
X/Twitter  → platform-specific resolver FIRST → yt-dlp optional fallback
```

For these three platforms, it is a task failure to keep the architecture as:

```text
PlatformDetector → youtubedl-android → yt-dlp → error
```

and then only translate or special-case the error.

### 1.3 yt-dlp can remain primary for other platforms

Unless evidence requires otherwise, the existing yt-dlp path can remain the primary implementation for:

- YouTube
- Facebook
- TikTok
- generic supported sites

Do not unnecessarily rewrite working platforms.

### 1.4 Do not add login/cookie UI as a shortcut

Current scope remains public content.

Do **not** respond to public-content extraction failures by adding:

- browser cookie extraction;
- cookies.txt import;
- account login UI;
- WebView credential capture;
- hard-coded session tokens;
- private API credentials.

If a reference implementation requires a secret, private token, authenticated API, or user login, document that limitation and select another public approach.

---

## 2. Current real-device evidence — must be treated as regression input

Owner tested the CI APK and confirmed the effective runtime version is:

```text
yt-dlp: 2026.08.19
```

### Instagram failures

Two public Reel URLs were reported to fail during analysis with:

```text
WARNING: [Instagram] ... Instagram API is not granting access
```

Regression inputs:

```text
https://www.instagram.com/reel/DdXfJ2nzdtA/?stkn=ZWhiNjI5c2NqcGpn
https://www.instagram.com/reel/DdTezS7Onyv/?stkn=MXFvZXhvY3dya3h0eA==
```

The existence of an up-to-date yt-dlp runtime does not solve this case.  
Do not keep retrying the same yt-dlp extractor as the primary fix.

### Threads failures

Owner reported these share URLs fail as unsupported:

```text
https://www.threads.com/share/_x6PzKrLo/
https://www.threads.com/share/BADtlftVG7/
```

Required behavior:

```text
/share/<token>
  ↓ follow real redirect / canonical URL
canonical Threads post
  ↓ identify target post only
extract target media
```

The resolver must not accidentally select recommended / related / quoted media unless the target post semantics require it.

### X / Twitter failures

Owner reported these URLs returned:

```text
ERROR: [twitter] ... No video could be found in this tweet
```

Regression inputs:

```text
https://x.com/SmallQQQQQ/status/2100222618649682262
https://x.com/Handjob12s/status/2100621013876887914
```

Treat these as owner-supplied regression inputs.

If inspection proves that a specific post genuinely contains no public video at test time, do not fake success.  
Record evidence and additionally test a confirmed public X video post using the same resolver path.

---

## 3. Mandatory research phase — DO THIS BEFORE WRITING THE FIX

The agent must not start by editing `YtDlpDownloadEngine.kt`.

Before implementation:

1. Read:
   - `AGENTS.md`
   - current `README.md`
   - this handoff
   - current PR #2
   - all current source files in `data/download/`
   - current platform detection / share URL code.

2. Inspect current upstream/reference implementations for actual extraction behavior.

3. Produce a short implementation note in the PR conversation or commit documentation containing, for each of Instagram / Threads / X:
   - reference repository;
   - exact source file(s) / relevant function(s);
   - URL / endpoint / page payload technique used;
   - whether authentication is required;
   - whether it is suitable for Android public-content use;
   - what logic will be ported or adapted;
   - what will deliberately NOT be copied.

4. Only after this reference comparison may implementation begin.

### Known references to inspect

These are starting points, not permission to blindly copy code:

- `JunkFood02/Seal`
  - mature Android downloader architecture and youtubedl-android integration patterns.
- `yausername/youtubedl-android`
  - Android runtime behavior / process / FFmpeg integration reference.
- `tribixbite/yt-dlp-threads`
  - Threads-specific extractor behavior reference.
- `ThePotato456/threads-ytdlp-extractor`
  - additional Threads extractor reference.
- `2Xsave/2xsave_common`
  - portable social-media extraction/common logic candidate.
- current public Android Instagram / X downloader repositories found through GitHub search
  - inspect implementation quality, maintenance status, authentication assumptions, and licensing before adapting.

If previous project discussion or PR comments contain additional Android reference repositories, inspect those too.

### Reference-code rule

Do not cargo-cult.

For every external implementation used:

- understand the actual HTTP flow;
- verify it still works against the current site;
- check license compatibility;
- adapt the minimum required algorithm into our application-owned abstraction;
- do not copy unrelated UI, analytics, ads, credentials, or tracking code.

---

## 4. Required architecture

Keep the existing `DownloadEngine` app boundary, but introduce explicit platform resolver routing.

Recommended shape:

```text
MainViewModel
    ↓
DownloadRepository
    ↓
Hybrid / Routed DownloadEngine
    ↓
PlatformResolverRouter
    ├── InstagramResolver
    ├── ThreadsResolver
    ├── XResolver
    └── YtDlpResolver / existing yt-dlp path
```

Exact class names may differ, but the separation must be real.

### 4.1 Resolver contract

Create an app-owned interface similar to:

```kotlin
interface PlatformResolver {
    fun supports(url: String): Boolean

    suspend fun resolve(url: String): Result<ResolvedMedia>
}
```

The app-owned resolved result should contain enough information to download without exposing third-party classes:

```text
canonicalUrl
platform
title
uploader?
thumbnail?
duration?
media candidates
  - direct progressive URL, OR
  - video URL + audio URL
  - height / bitrate when trustworthy
headers/referer needed for media request
```

Do not expose raw yt-dlp classes to UI.

### 4.2 Download path

If a platform resolver returns direct media URLs, download them directly through the Android engine.

Do not force a successfully resolved direct URL back through yt-dlp merely because yt-dlp already exists.

Use FFmpeg only when required for:

- separate video + audio merge;
- audio extraction / conversion.

### 4.3 Fallback semantics

Fallback must be explicit and observable.

Example:

```text
InstagramResolver
  ├─ success → use native result
  └─ unsupported / no public media result
        ↓
     yt-dlp fallback (optional)
```

A resolver exception caused by a programming bug must not silently fall through and hide the bug.

Log which resolver path was used in debug diagnostics.

---

## 5. Platform-specific requirements

## 5.1 Instagram

Goal: public Reel / public video post metadata + downloadable media without account login.

The primary implementation must be based on a currently working public-page / portable extractor technique discovered in the mandatory research phase.

Requirements:

- normalize Reel/post URL and remove irrelevant share query parameters when safe;
- use realistic browser HTTP headers where required;
- follow redirects;
- handle escaped JSON / HTML entities correctly;
- locate media belonging to the target shortcode only;
- return direct media URL(s), title/caption, thumbnail when available;
- do not claim private/login-required content is supported;
- do not call the yt-dlp Instagram extractor first.

If the public website blocks all unauthenticated extraction for the tested URL, provide concrete HTTP/payload evidence before declaring a platform limitation.

## 5.2 Threads

Threads requires a dedicated resolver.

Required URL forms include at minimum:

```text
https://www.threads.com/@user/post/<code>
https://www.threads.com/t/<code>
https://www.threads.com/share/<token>
threads.net equivalents when encountered
```

Requirements:

1. Normalize `threads.net` → `threads.com` where appropriate.
2. For `/share/<token>`, resolve redirects/canonical URL before extracting a post ID.
3. Support:
   - `application/json` payloads;
   - escaped/nested JSON payloads when the target post is not present as a simple JSON block.
4. Match the target post by shortcode/code.
5. Once target post is found, traverse only the target post's relevant media subtree.
6. Explicitly avoid:
   - recommended;
   - related;
   - suggested;
   - unrelated page media.
7. Support progressive video when available.
8. Support DASH video + audio when required.
9. If DASH is used:
   - parse Representation choices;
   - choose a real best video representation;
   - choose audio representation;
   - merge with FFmpeg;
   - merge failure = failure, never silent video-only success.
10. Do not display fake 1080p/720p options that map to the same URL.

The existing simplified `ThreadsResolver.kt` may be replaced or substantially rewritten if that is the smallest correct solution.

## 5.3 X / Twitter

Do not assume `No video could be found in this tweet` from yt-dlp proves the post has no video.

The primary X resolver must be based on a current public extraction method discovered during research.

Requirements:

- normalize `twitter.com` / `x.com`;
- identify status ID;
- resolve target tweet/media only;
- obtain available video variants when public;
- select the best sensible variant using bitrate/resolution metadata;
- preserve required Referer/User-Agent/headers;
- return a clear "no public video in target post" only when the primary resolver also confirms no video.

Do not add undocumented bearer tokens, private API keys, or copied secrets.

If a public endpoint requires a guest/session token obtained through a documented public bootstrap flow, explain and encapsulate that flow; do not hard-code transient credentials.

---

## 6. HTTP and parsing requirements

For platform-native resolvers:

- use Android-compatible HTTP code;
- set explicit timeouts;
- follow redirects deliberately;
- support per-request headers;
- use a browser-like User-Agent where needed;
- use Referer where media CDN requires it;
- decode HTML entities and JSON escapes;
- sanitize logging;
- close streams/connections;
- cancellation must interrupt active network work;
- temporary files must stay private until successful completion.

Prefer one small HTTP abstraction shared by platform resolvers rather than duplicating fragile connection code three times, but do not introduce a large framework merely for this milestone unless justified.

---

## 7. Error model

Do not expose raw extractor warnings as the primary user message.

Map failures into app-owned categories such as:

```text
NoPublicMedia
LoginRequired
RateLimited
UnsupportedLayout
NetworkFailure
ResolverChanged
PostProcessingFailure
StorageFailure
Cancelled
```

Debug logs may include sanitized low-level details.

User-visible error examples should be concise:

- 「此公開貼文目前找不到可下載影片」
- 「來源網站要求登入，目前版本不支援登入內容」
- 「來源網站頁面格式已變更，解析器需要更新」

Do not show multi-line raw yt-dlp WARNING/ERROR text directly in the main error card.

---

## 8. Runtime strategy

Keep `youtubedl-android 0.18.1` only where it remains useful.

Current app runtime self-update to Stable may remain if it is already stable and tested, but:

- runtime update is not the fix for Instagram/Threads/X native resolver failures;
- do not repeatedly update yt-dlp trying to solve a resolver that should be platform-native;
- runtime version must remain visible in diagnostics.

Do not change FFmpeg packaging strategy unless required by an actual failure.

The known 16 KB page-size limitation remains documented and is not solved by this task unless a minimal validated dependency update is available.

---

## 9. Tests — CI success alone is NOT acceptance

### 9.1 Mandatory unit tests

Add deterministic tests for:

- routing:
  - Instagram URL → InstagramResolver first;
  - Threads URL → ThreadsResolver first;
  - X URL → XResolver first;
  - YouTube / generic → current yt-dlp path.
- URL normalization for each platform.
- Threads `/share/` canonical resolution logic using mocked/local responses.
- target-post selection that rejects unrelated/recommended media.
- escaped JSON payload parsing.
- direct progressive media selection.
- DASH best-video + audio selection.
- merge failure returns failure.
- resolver fallback semantics.
- user-facing error mapping.

Do not make CI depend on live third-party URLs.

### 9.2 Real-device acceptance gate

A PR is **not ready to merge** until the owner tests a new CI APK and confirms the regression platforms.

At minimum, report:

```text
Device:
Android version:
APK CI run:
App commit:
yt-dlp runtime:

Instagram:
- URL:
- resolver used:
- analyze:
- download:
- playable/audio:

Threads:
- /share URL:
- canonical URL resolved:
- resolver used:
- analyze:
- download:
- playable/audio:

X:
- URL:
- resolver used:
- analyze:
- download:
- playable/audio:
```

If a supplied live URL no longer contains public media, replace it with another confirmed public-media URL and explain why.

### 9.3 Existing baseline regression

Do not break currently working behavior:

- YouTube analyze/download
- Facebook basic public path
- share intent
- manual paste
- cancellation
- MediaStore save
- completion/failure notification

---

## 10. Diagnostics required for this recovery task

In debug builds, log a short resolver trace such as:

```text
[Resolver] platform=THREADS route=ThreadsResolver
[Resolver] share_redirect=true canonical=https://...
[Resolver] target_code=...
[Resolver] candidates=2 selected=dash height=1080 audio=true
```

For fallback:

```text
[Resolver] platform=INSTAGRAM primary=InstagramResolver result=NoPublicMedia
[Resolver] fallback=yt-dlp
```

Never log:

- cookies;
- auth headers;
- tokens;
- full credential-bearing URLs;
- personal account data.

This diagnostic trace is required so future real-device screenshots/logs show which engine actually ran.

---

## 11. Workflow for this task

Continue the existing work:

```text
Repository: charleswoo1/video_downloader_android
PR: #2
Branch: feat/android-v0.1.0-initial
```

Do **not**:

- create a new PR for this recovery unless PR #2 becomes unusable;
- merge PR #2;
- modify `main` directly;
- create a tag;
- publish a Release.

Before pushing, confirm the branch still corresponds to PR #2.

After implementation:

```text
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

CI must upload the new debug APK Artifact.

---

## 12. Stop conditions

Stop and report evidence instead of choosing a different architecture silently if:

1. A reference implementation only works with authenticated/private APIs.
2. A platform now requires login for the owner's public regression URLs.
3. A required public endpoint is protected by a secret/token that cannot be legitimately obtained.
4. The selected reference code has incompatible licensing for adaptation.
5. The fix would require WebView credential capture, accessibility, broad storage permission, or other invasive mechanisms.
6. The only proposed fix is "update yt-dlp again" for Instagram/Threads/X without first implementing/evaluating the required native resolver.
7. A resolver cannot be validated against current website payloads.

When stopping, include:

- HTTP status / sanitized response evidence;
- reference implementation evaluated;
- exact blocker;
- smallest next option.

---

## 13. Definition of done for this recovery

The task is complete only when all are true:

- [ ] Mandatory reference research is documented.
- [ ] Instagram has a platform-specific primary resolver.
- [ ] Threads has a platform-specific primary resolver with working `/share/` handling.
- [ ] X/Twitter has a platform-specific primary resolver.
- [ ] yt-dlp is no longer the unconditional first path for those three platforms.
- [ ] Resolver routing is covered by tests.
- [ ] Raw yt-dlp warnings are not shown directly as normal UI errors.
- [ ] Threads target-post selection does not use recommendation media.
- [ ] Direct stream downloads support required headers/referer.
- [ ] DASH merge failure is a real failure.
- [ ] Unit tests pass.
- [ ] Lint passes.
- [ ] `assembleDebug` passes.
- [ ] CI passes and uploads APK.
- [ ] Existing YouTube/Facebook baseline is not regressed.
- [ ] Owner receives a new APK for real-device testing.
- [ ] Instagram/Threads/X are not marked PASS until real-device verification.
- [ ] No merge/tag/release occurs without owner instruction.

---

## 14. Required agent report

Return exactly this information when implementation is ready for owner testing:

```text
Reference research:
- Instagram:
- Threads:
- X:

Architecture:
- routing changes:
- new resolver classes:
- yt-dlp fallback behavior:

PR:
Branch:
Head commit:

Tests:
Unit tests:
Lint:
assembleDebug:
CI run:
Artifact:

Regression status:
- YouTube:
- Facebook:
- Instagram: NEEDS OWNER DEVICE TEST / PASS / BLOCKED
- Threads: NEEDS OWNER DEVICE TEST / PASS / BLOCKED
- X: NEEDS OWNER DEVICE TEST / PASS / BLOCKED

Diagnostics added:
Known limitations:
Release created: NO
Merged: NO
```

Do not report Instagram / Threads / X as PASS merely because unit tests or CI pass.
