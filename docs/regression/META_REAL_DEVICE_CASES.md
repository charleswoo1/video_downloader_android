# Meta Platforms Real-Device Regression Corpus

This document records owner-supplied real-device test cases for Instagram, Threads, and X to support A/B comparison between Native Engines and the modern yt-dlp runtime baseline.

> **Engineering Test Data Notice**:
> - These are live third-party posts that may change or be removed over time.
> - **Do NOT** execute live network requests against these URLs during automated CI.
> - Parser unit tests use offline sanitized fixtures under `app/src/test/resources/fixtures/`.
> - Recorded date: 2026-09-18.

---

## 1. Threads Cases

### TH-SHARE-FAIL-01 (Mandatory Handoff Case)
- **Input URL**: `https://www.threads.com/share/_x6PzKrLo/`
- **yt-dlp Baseline Result**: FAIL (`Unsupported URL` or `Post was not found in the page data`)
- **Native Engine Goal**: Follow HTTP redirect / canonical meta tags to canonical post URL and extract target video without grabbing feed recommendations.
- **Verification Status**: `NEEDS OWNER DEVICE TEST`

### TH-SHARE-FAIL-02 (Mandatory Handoff Case)
- **Input URL**: `https://www.threads.com/share/BADtlftVG7/`
- **yt-dlp Baseline Result**: FAIL (`Unsupported URL` or `Post was not found in the page data`)
- **Native Engine Goal**: Follow HTTP redirect / canonical meta tags to canonical post URL and extract target video without grabbing feed recommendations.
- **Verification Status**: `NEEDS OWNER DEVICE TEST`

### TH-SHARE-PASS-01 (Non-Regression Verification)
- **Input URL**: `https://www.threads.com/share/BAPaySXLil/`
- **yt-dlp Baseline Result**: PASS (Metadata extracted successfully)
- **Native Engine Goal**: Non-regression verification. Native engine should resolve share link and extract progressive/DASH video.
- **Verification Status**: `NEEDS OWNER DEVICE TEST` (Offline fixture verified)

### TH-SHARE-PASS-02 (Non-Regression Verification)
- **Input URL**: `https://www.threads.com/share/_6syxcd_8/`
- **yt-dlp Baseline Result**: PASS (Metadata extracted successfully)
- **Native Engine Goal**: Non-regression verification. Native engine should resolve share link and extract progressive/DASH video.
- **Verification Status**: `NEEDS OWNER DEVICE TEST` (Offline fixture verified)

---

## 2. Instagram Cases

### IG-FAIL-API-01 (Mandatory Handoff Case)
- **Input URL**: `https://www.instagram.com/reel/DdXfJ2nzdtA/?stkn=ZWhiNjI5c2NqcGpn`
- **yt-dlp Baseline Result**: FAIL (`WARNING: [Instagram] ... Instagram API is not granting access`)
- **Native Engine Goal**: Extract media from public anonymous page data / `xdt_api__v1__media__shortcode__web_info` / `data-sjs` with RFC percent decoding preserving signed token `stkn`.
- **Verification Status**: `NEEDS OWNER DEVICE TEST`

### IG-FAIL-API-02 (Mandatory Handoff Case)
- **Input URL**: `https://www.instagram.com/reel/DdTezS7Onyv/?stkn=MXFvZXhvY3dya3h0eA==`
- **yt-dlp Baseline Result**: FAIL (`WARNING: [Instagram] ... Instagram API is not granting access`)
- **Native Engine Goal**: Extract media from public anonymous page data / `xdt_api__v1__media__shortcode__web_info` / `data-sjs` with RFC percent decoding preserving signed token `stkn`.
- **Verification Status**: `NEEDS OWNER DEVICE TEST`

### IG-PASS-01 (Non-Regression Verification)
- **Input URL**: `https://www.instagram.com/reel/DdS5sMrxkBq/` (Shortcode: `DdS5sMrxkBq`)
- **yt-dlp Baseline Result**: PASS (Metadata extracted successfully)
- **Native Engine Goal**: Non-regression verification. Native engine should extract video URL and quality options.
- **Verification Status**: `NEEDS OWNER DEVICE TEST` (Offline fixture verified)

### IG-AUDIENCE-01 (Restriction Classification)
- **Input URL**: `https://www.instagram.com/reel/DbtoOl8zwMO/` (Shortcode: `DbtoOl8zwMO`)
- **Observed Baseline Result**: Restriction (`This content isn't available to everyone. It can't be seen by certain audiences.`)
- **Classification**: Content/Access Restriction (`AUDIENCE_RESTRICTED`).
- **Policy**: Must terminate immediately with clear zh-TW error message without entering a fallback loop to yt-dlp.
- **Verification Status**: `NEEDS OWNER DEVICE TEST` (Offline fixture verified)

---

## 3. X (Twitter) Cases

### X-FAIL-NOVIDEO-01 (Mandatory Handoff Case)
- **Input URL**: `https://x.com/SmallQQQQQ/status/2100222618649682262`
- **Observed yt-dlp Baseline Result**: FAIL (`ERROR: [twitter] ... No video could be found in this tweet`)
- **Native Engine Goal**: Query GraphQL `TweetResultByRestId` / HTML fallback using guest token and activate client transaction ID. If genuinely text/photo-only, classify as terminal `NO_VIDEO` without fallback loop; if video exists, extract highest bitrate MP4.
- **Verification Status**: `NEEDS OWNER DEVICE TEST`

### X-FAIL-NOVIDEO-02 (Mandatory Handoff Case)
- **Input URL**: `https://x.com/Handjob12s/status/2100621013876887914`
- **Observed yt-dlp Baseline Result**: FAIL (`ERROR: [twitter] ... No video could be found in this tweet`)
- **Native Engine Goal**: Query GraphQL `TweetResultByRestId` / HTML fallback using guest token and activate client transaction ID. If genuinely text/photo-only, classify as terminal `NO_VIDEO` without fallback loop; if video exists, extract highest bitrate MP4.
- **Verification Status**: `NEEDS OWNER DEVICE TEST`

### X-PASS-01 (Offline Fixture Only)
- **Input URL**: `https://x.com/Twitter/status/1234567890` (Verified via offline sanitized fixture `video_tweet_graphql.json`)
- **yt-dlp Baseline Result**: `NOT APPLICABLE / FIXTURE ONLY`
- **Native Engine Goal**: Non-regression verification for public video tweets via GraphQL `TweetResultByRestId` and highest-bitrate rendition selection.
- **Verification Status**: Unit test with offline fixture PASSED; live network extraction marked as `NOT APPLICABLE / FIXTURE ONLY`.

---

## 4. Real-Device A/B Matrix

| Case ID | Input URL / Shortcode | Native Engine Result | yt-dlp Result | Final Engine Route | Notes |
|---|---|---|---|---|---|
| `TH-SHARE-FAIL-01` | `https://www.threads.com/share/_x6PzKrLo/` | `NEEDS OWNER DEVICE TEST` | FAIL | `NativeThreadsEngine` | Mandatory handoff case; requires owner device test |
| `TH-SHARE-FAIL-02` | `https://www.threads.com/share/BADtlftVG7/` | `NEEDS OWNER DEVICE TEST` | FAIL | `NativeThreadsEngine` | Mandatory handoff case; requires owner device test |
| `TH-SHARE-PASS-01` | `https://www.threads.com/share/BAPaySXLil/` | `NEEDS OWNER DEVICE TEST` | PASS | `NativeThreadsEngine` | Offline fixture verified; requires owner device test |
| `TH-SHARE-PASS-02` | `https://www.threads.com/share/_6syxcd_8/` | `NEEDS OWNER DEVICE TEST` | PASS | `NativeThreadsEngine` | Offline fixture verified; requires owner device test |
| `IG-FAIL-API-01` | `.../reel/DdXfJ2nzdtA/?stkn=ZWhiNjI5c2NqcGpn` | `NEEDS OWNER DEVICE TEST` | FAIL | `NativeInstagramEngine` | Mandatory handoff case; requires owner device test |
| `IG-FAIL-API-02` | `.../reel/DdTezS7Onyv/?stkn=MXFvZXhvY3dya3h0eA==` | `NEEDS OWNER DEVICE TEST` | FAIL | `NativeInstagramEngine` | Mandatory handoff case; requires owner device test |
| `IG-PASS-01` | `.../reel/DdS5sMrxkBq/` | `NEEDS OWNER DEVICE TEST` | PASS | `NativeInstagramEngine` | Offline fixture verified; requires owner device test |
| `IG-AUDIENCE-01` | `.../reel/DbtoOl8zwMO/` | `NEEDS OWNER DEVICE TEST` | Audience restriction | Terminated (no loop) | Expected restriction; offline fixture verified |
| `X-FAIL-NOVIDEO-01` | `https://x.com/SmallQQQQQ/status/2100222618649682262` | `NEEDS OWNER DEVICE TEST` | FAIL (No video) | `NativeXEngine` | Mandatory handoff case; requires owner device test |
| `X-FAIL-NOVIDEO-02` | `https://x.com/Handjob12s/status/2100621013876887914` | `NEEDS OWNER DEVICE TEST` | FAIL (No video) | `NativeXEngine` | Mandatory handoff case; requires owner device test |
| `X-PASS-01` | `1234567890` (Fixture) | `NEEDS OWNER DEVICE TEST` | `NOT APPLICABLE / FIXTURE ONLY` | `NativeXEngine` | Offline fixture unit test PASSED; fixture only |


