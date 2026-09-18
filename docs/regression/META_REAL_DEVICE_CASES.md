# Meta Platforms Real-Device Regression Corpus

This document records owner-supplied real-device test cases for Threads and Instagram to support A/B comparison between Native Meta Engines and the modern yt-dlp runtime baseline.

> **Engineering Test Data Notice**:
> - These are live third-party posts that may change or be removed over time.
> - **Do NOT** execute live network requests against these URLs during automated CI.
> - Parser unit tests use offline sanitized fixtures under `app/src/test/resources/fixtures/`.
> - Recorded date: 2026-09-18.

---

## 1. Threads Cases

### TH-SHARE-FAIL-PAGEDATA-01
- **Input URL**: `https://www.threads.com/share/_2DcaFS7L/`
- **Observed Canonical Shortcode**: `DdZEpoeGbro`
- **yt-dlp Baseline Result**: FAIL (`Post "DdZEpoeGbro" was not found in the page data.`)
- **Native Engine Goal**: Determine whether anonymous public page/embed data exposes target media using the native request/parser path.

### TH-SHARE-PASS-01
- **Input URL**: `https://www.threads.com/share/BAPaySXLil/`
- **yt-dlp Baseline Result**: PASS (Metadata extracted successfully)
- **Native Engine Goal**: Non-regression verification. Native engine should resolve share link and extract progressive/DASH video.

### TH-SHARE-FAIL-PAGEDATA-02
- **Input URL**: `https://www.threads.com/share/BAYRqEnpRR/`
- **Observed Canonical Shortcode**: `DdY8E2dABUv`
- **yt-dlp Baseline Result**: FAIL (`Post "DdY8E2dABUv" was not found in the page data.`)
- **Native Engine Goal**: Determine whether anonymous public page/embed data exposes target media using the native request/parser path.

### TH-SHARE-PASS-02
- **Input URL**: `https://www.threads.com/share/_6syxcd_8/`
- **yt-dlp Baseline Result**: PASS (Metadata extracted successfully)
- **Native Engine Goal**: Non-regression verification. Native engine should resolve share link and extract progressive/DASH video.

---

## 2. Instagram Cases

### IG-EMPTY-MEDIA-01
- **Input URL**: `https://www.instagram.com/reel/DdXfLoyTs__/` (Shortcode: `DdXfLoyTs__`)
- **yt-dlp Baseline Result**: FAIL (`Instagram sent an empty media response`)
- **Native Engine Goal**: Determine whether anonymous server-rendered page data (`data-sjs`, polaris, or schema.org) contains usable media.

### IG-PASS-01
- **Input URL**: `https://www.instagram.com/reel/DdS5sMrxkBq/` (Shortcode: `DdS5sMrxkBq`)
- **yt-dlp Baseline Result**: PASS (Metadata extracted successfully)
- **Native Engine Goal**: Non-regression verification. Native engine should extract video URL and quality options.

### IG-AUDIENCE-01
- **Input URL**: `https://www.instagram.com/reel/DbtoOl8zwMO/` (Shortcode: `DbtoOl8zwMO`)
- **Observed Baseline Result**: Restriction (`This content isn't available to everyone. It can't be seen by certain audiences.`)
- **Classification**: Content/Access Restriction (`AUDIENCE_RESTRICTED`).
- **Policy**: Must terminate immediately with clear zh-TW error message without entering a fallback loop to yt-dlp.

---

## 3. Real-Device A/B Matrix

| Case ID | Input Shortcode / Share URL | Native Engine Result | yt-dlp Plugin Result | Final Engine Route | Notes |
|---|---|---|---|---|---|
| `TH-SHARE-PASS-01` | `BAPaySXLil` | PASS | PASS | `NativeThreadsEngine` | Non-regression pass |
| `TH-SHARE-PASS-02` | `_6syxcd_8` | PASS | PASS | `NativeThreadsEngine` | Non-regression pass |
| `TH-SHARE-FAIL-PAGEDATA-01` | `_2DcaFS7L` (`DdZEpoeGbro`) | Pending device test | FAIL (page data missing) | `PlatformEngineRouter` | Native extraction attempt |
| `TH-SHARE-FAIL-PAGEDATA-02` | `BAYRqEnpRR` (`DdY8E2dABUv`) | Pending device test | FAIL (page data missing) | `PlatformEngineRouter` | Native extraction attempt |
| `IG-PASS-01` | `DdS5sMrxkBq` | PASS | PASS | `NativeInstagramEngine` | Non-regression pass |
| `IG-EMPTY-MEDIA-01` | `DdXfLoyTs__` | Pending device test | FAIL (empty media) | `PlatformEngineRouter` | Native extraction attempt |
| `IG-AUDIENCE-01` | `DbtoOl8zwMO` | Audience restriction | Audience restriction | Terminated (no loop) | Expected restriction |
