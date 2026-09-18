# Upstream Extractor Reference Registry

Last reviewed: 2026-09-18

This document is the durable upstream-reference registry for `video_downloader_android`.
Agents working on platform extraction MUST read this document before modifying Instagram, Threads, X/Twitter, cookie/session, or multi-engine routing code.

The repository source of truth remains:
`charleswoo1/video_downloader_android`

## Rules

1. Never "blind patch" an extractor when a maintained upstream implementation exists.
2. Check the pinned commit first. If upstream has advanced, inspect the delta and record the newer commit before adopting behavior.
3. Preserve license boundaries:
   - MIT: implementation/algorithm may be ported with required attribution.
   - Unlicense/public domain: implementation may be reused subject to project review.
   - GPL-3.0/AGPL-3.0: behavior, architecture, public data shapes and test ideas may be studied, but do not copy or line-for-line translate source into this repository unless the project deliberately changes licensing.
4. Cobalt/server-backend integration is currently out of scope.
5. Do not add runtime network dependencies on these GitHub repositories. Upstream code is research/build-time reference only unless explicitly approved.

---

## Tier A — Primary implementation references

### 1. 2Xsave/insave

Repository:
`https://github.com/2Xsave/insave`

Pinned commit:
`6454affbd8e7960db012c81ffc1c213b75e8947b`

Last observed push:
2026-09-03

License:
MIT

Purpose:
Primary reference for Native Instagram extraction.

Read first:
- `src/client.rs`
- `src/metadata.rs`
- `src/config.rs`
- `src/constants.rs`
- `src/lib.rs`

Key behaviors to study/port independently into Kotlin:
- browser identity/request headers rather than a single User-Agent;
- session cookie jar;
- redirect handling;
- retry/backoff behavior;
- parsing all relevant `<script type="application/json">` payloads;
- support for newer Instagram payload shapes including `xdt_api__v1__media__shortcode__web_info`;
- target media identification using shortcode/media id/pk rather than "first video on page";
- recursive `video_versions`, `carousel_media`, and `image_versions2` handling;
- normalization of escaped/percent-encoded CDN URLs;
- acceptance of Meta CDN URLs that do not end in `.mp4`.

Required attribution if implementation is ported:
Preserve MIT copyright/license notice in project third-party notices.

---

### 2. 2Xsave/trsave

Repository:
`https://github.com/2Xsave/trsave`

Pinned commit:
`2841945254c24c1ad3f3658410788503bcdced72`

Last observed push:
2026-09-03

License:
MIT

Purpose:
Primary reference for Native Threads extraction.

Read first:
- `src/client.rs`
- `src/metadata.rs`
- `src/config.rs`
- `src/constants.rs`
- `src/lib.rs`

Key behaviors to study/port:
- desktop/mobile browser identities with coherent `sec-ch-ua`, fetch metadata, language and navigation headers;
- cookie jar and redirect state;
- retry + jitter/backoff;
- scanning all application/json script payloads;
- canonical target shortcode isolation;
- recursive `video_versions`, `image_versions2`, carousel and nested-media search;
- URL normalization for escaped/percent-encoded Meta CDN URLs;
- accepting Meta CDN video paths without relying on a literal `.mp4` suffix;
- direct-media request headers separate from navigation headers.

Do not weaken target-post isolation. Never fall back to "first video in HTML".

---

### 3. 2Xsave/twsave

Repository:
`https://github.com/2Xsave/twsave`

Pinned commit:
`0da4dd7db8a0f93445338821309cff736c79b9ec`

Last observed push:
2026-09-03

License:
MIT

Purpose:
Primary reference for Native X/Twitter extraction.

Read first:
- `src/client.rs`
- `src/metadata.rs`
- `src/config.rs`
- `src/constants.rs`
- `src/lib.rs`

Key behaviors to study/port:
- browser identity/session cookie jar;
- dynamic Bearer token discovery from X HTML / responsive-web JS, with fallback token only as a last resort;
- guest-token acquisition from cookie/header/API flow;
- GraphQL request construction and current feature flags;
- 401/403 token refresh/retry path;
- parsing `tweetResult.result.legacy`;
- `extended_entities.media[*].video_info.variants`;
- choose the highest-bitrate actual video rendition;
- distinguish photo/text/no-video from extractor failure;
- HTML/`window.__INITIAL_STATE__` fallback when GraphQL is unavailable;
- separate video-download request headers.

Security requirement:
Tokens used here are public web-client/guest-session mechanics, not user credentials. Never log tokens or expose them in UI.

---

### 4. 2Xsave/2xsave_common

Repository:
`https://github.com/2Xsave/2xsave_common`

Pinned commit:
`1d52f56aa214a3c4e3c965d480b52a08b1643281`

Last observed push:
2026-09-03

License:
MIT

Purpose:
Reference for common request/session configuration shared by platform engines.

Read:
- `src/config.rs`
- `src/constants.rs`
- `src/lib.rs`

Use to inform:
- common timeout configuration;
- desktop/mobile identity pools;
- language/header configuration;
- cookie/proxy/session concepts;
- shared retry policy.

Android V2 should implement these concepts natively in Kotlin/OkHttp rather than embedding Rust.

---

### 5. 2Xsave/2XsaveTUI

Repository:
`https://github.com/2Xsave/2XsaveTUI`

Pinned commit:
`301b6992bb5aa44c8a374ef1125981c77692de56`

Last observed push:
2026-09-03

License:
MIT

Purpose:
Architecture/reference for routing one product through multiple platform-specific engines.

Read:
- `src/downloader.rs`

Use to compare:
- platform detection;
- engine dispatch;
- shared output/download handling;
- per-platform failure boundaries.

Do not copy terminal UI concerns into Android.

---

## Tier B — Direct algorithm reference already used

### 6. tribixbite/yt-dlp-threads

Repository:
`https://github.com/tribixbite/yt-dlp-threads`

Pinned commit:
`c4c44141cb10715f94296a808f5d89a0d24dfe94`

License:
Unlicense / public domain

Purpose:
- current yt-dlp Threads plugin fallback;
- direct reference for Threads crawler behavior, share URL support, target-shortcode isolation and media parsing.

This remains bundled as the yt-dlp fallback baseline unless a later handoff explicitly removes it.

---

## Tier C — Active projects for behavior/session research only

### 7. Orang-Studio/InstaDownload

Repository:
`https://github.com/Orang-Studio/InstaDownload`

Pinned commit:
`f2992e79eb774fbc3897f483d58fd1730db54314`

Last observed push:
2026-09-17

License:
GPL-3.0

Read:
- `InstaDownload/app/src/main/java/com/vakarux/instadownload/InstagramDownloader.kt`

Why it matters:
- active pure-Kotlin Android Instagram implementation;
- Googlebot / public-page strategy;
- data-sjs parsing;
- shortcode/media-id targeting;
- current login-wall behavior.

License boundary:
Research behavior and page structures only. Do not copy or line-for-line translate implementation.

---

### 8. deniscerri/ytdlnis

Repository:
`https://github.com/deniscerri/ytdlnis`

Pinned commit:
`39ad9d59d4328136dab89da8ab47d705f81cad61`

Last observed push:
2026-09-17

License:
GPL-3.0

Useful files:
- `app/src/main/java/com/deniscerri/ytdl/ui/more/cookies/CookiesFragment.kt`
- `app/src/main/java/com/deniscerri/ytdl/ui/more/cookies/WebViewActivity.kt`
- `app/src/main/java/com/deniscerri/ytdl/util/extractors/ytdlp/YTDLPUtil.kt`
- `app/src/main/java/com/deniscerri/ytdl/util/extractors/newpipe/NewPipeUtil.kt`
- YouTube PoToken/WebView files under `util/extractors/newpipe/potoken/`

Why it matters:
- mature Android cookie/session UX;
- WebView-assisted authentication concepts;
- multi-extractor integration;
- yt-dlp/NewPipe coexistence.

Current V2 scope:
No login/cookie UI implementation yet. Use this to design a future `SessionProvider` boundary only.

License boundary:
Research only; do not copy implementation.

---

### 9. JunkFood02/Seal

Repository:
`https://github.com/JunkFood02/Seal`

Pinned commit:
`63bd8a4d31df9571c3d2d460079acccb76c35412`

Last observed push:
2026-08-25

License:
GPL-3.0

Useful files:
- `app/src/main/java/com/junkfood/seal/ui/page/settings/network/CookiesViewModel.kt`
- `app/src/main/java/com/junkfood/seal/ui/page/settings/network/WebViewPage.kt`

Why it matters:
- high-usage Android yt-dlp frontend;
- cookie/session management;
- WebView-based browser session concepts;
- real-world Android downloader UX.

License boundary:
Research only; no source copying.

---

### 10. TeamNewPipe/NewPipeExtractor

Repository:
`https://github.com/TeamNewPipe/NewPipeExtractor`

Pinned commit:
`ab984a8e3bcd0bc6d4b5f90860815f7b10476541`

Default branch:
`dev`

Last observed push:
2026-09-17

License:
GPL-3.0

Why it matters:
- mature JVM extractor architecture;
- strong typed exception taxonomy;
- downloader/request abstraction;
- useful model for separating parsing, content restrictions, and network failures.

Important:
It is not a replacement for Instagram/Threads/X coverage in this project.

Useful architectural areas:
- `extractor/.../Downloader.java`
- `extractor/.../Request.java`
- `extractor/.../Response.java`
- exception classes such as:
  - `PrivateContentException`
  - `AgeRestrictedContentException`
  - `ContentNotAvailableException`
  - `GeographicRestrictionException`
  - `ParsingException`
  - `ReCaptchaException`

License boundary:
Architecture/error-taxonomy inspiration only.

---

## Explicitly out of scope

### Cobalt

Cobalt/server-backend architecture is deliberately excluded from the current Android plan at the owner's request.

Do not add:
- Cobalt API;
- self-hosted server requirement;
- remote downloader backend;
- server-side extraction as a fallback.

A future owner-approved architecture change would require a separate handoff.

---

## Maintenance process

Before a platform-extractor redesign:

1. Read this registry.
2. Check each Tier A repo for commits newer than the pinned SHA.
3. If newer:
   - inspect relevant diffs;
   - update the reference SHA in a dedicated docs commit;
   - summarize behavior changes before implementation.
4. Keep real-device regression cases separate from mandatory CI.
5. Any ported MIT code/algorithm must be documented in `THIRD_PARTY_NOTICES.md`.
6. Never treat a web-platform parser as permanently stable; preserve deterministic fallback and diagnostics.
