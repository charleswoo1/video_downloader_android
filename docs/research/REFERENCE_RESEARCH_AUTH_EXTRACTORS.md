# Reference Research & Implementation Comparison: Authentication & Platform Extractors

**Document path:** `docs/research/REFERENCE_RESEARCH_AUTH_EXTRACTORS.md`  
**Governing specification:** `docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`  
**Branch:** `feat/android-v0.1.0-initial`  
**Date:** 2026-09-20  

---

## 1. Project Mandate & Reference-First Gate

In accordance with `HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`, blind-patching extractors for **Instagram**, **Threads**, and **X (Twitter)** is strictly prohibited. Real-device evidence from CI #37 proved that anonymous public extraction alone is insufficient:
- **Instagram**: Public page requests can return HTTP 200 with JS wrapper shells but no media node (`WRAPPER_SEEN_NO_MEDIA` / `TARGET_NOT_IN_PAGE`), or trigger an ambiguous error message ("Requested content is not available, rate-limit reached or login required") that was previously misclassified as confirmed rate-limiting.
- **Threads**: Public web pages are frequently rendered as client-side empty JS shells (`stage=NO_TARGET_FOUND`) without server-rendered JSON, causing script-scraping extractors to fail.
- **X (Twitter)**: Adult / age-restricted tweets return `TweetTombstone` or `TweetUnavailable` with reasons like `NsfwLoggedOut` and `NsfwViewerHasNoStatedAge`. Anonymous guest-token requests are categorically blocked by X for adult content.

Before implementing any extractor modifications, upstream and platform-specific reference implementations were researched, verified, and compared against the current Android codebase.

---

## 2. Platform Reference Analysis & Flow Contracts

### 2.1 Instagram

#### Upstream References Inspected
1. **`yt-dlp/yt-dlp` (`yt_dlp/extractor/instagram.py`)** (Unlicense)
   - Pinned / inspected commit: upstream `master` (2026-09)
   - Relevant routines: `_real_extract`, `_is_logged_in`, `_api_headers`, `_extract_product`, `_extract_product_media`
2. **`2Xsave/insave`** (MIT)
   - Pinned commit: `6454affbd8e7960db012c81ffc1c213b75e8947b`
   - Relevant routines: `src/client.rs`, `src/metadata.rs`
3. **`Orang-Studio/InstaDownload`** (GPL-3.0, architectural/behavioral study only)
   - Pinned commit: `f2992e79eb774fbc3897f483d58fd1730db54314`

#### Reference Flow Contracts

##### A. Authenticated Path (`yt-dlp`)
- **Session detection**: `sessionid` cookie present for `.instagram.com` / `i.instagram.com`.
- **Endpoint**: `GET https://www.instagram.com/api/v1/media/{media_id}/info/` (or `https://i.instagram.com/api/v1/media/{media_id}/info/`).
- **Headers**:
  ```http
  X-IG-App-ID: 936619743392459
  X-ASBD-ID: 359341
  X-IG-WWW-Claim: 0
  Origin: https://www.instagram.com
  Accept: */*
  Cookie: sessionid=...; ds_user_id=...; csrftoken=...
  ```
- **Response Structure**:
  `items[0]` contains the full media payload: `video_versions` (highest quality rendition, codec, resolution) or `carousel_media` (multi-item posts).
- **Session Expiration / Invalidation**:
  If HTTP response redirects to `/accounts/login` or returns 401/403 with login redirect, the session cookie has expired. Upstream warns and falls back to logged-out extraction while invalidating the cached cookie.

##### B. Anonymous Path (`yt-dlp`)
1. **Pre-flight content ruling**:
   `GET https://www.instagram.com/api/v1/web/get_ruling_for_content/?content_type=MEDIA&target_id={media_id}`
   - Sets CSRF token via cookies.
   - If response `status != "ok"` or message contains `"Restricted Video"`, classifies as `AUTH_REQUIRED`.
2. **Polaris Logged-out Desktop GraphQL Query**:
   `POST https://www.instagram.com/api/graphql`
   - **Headers**:
     ```http
     X-FB-Friendly-Name: PolarisLoggedOutDesktopWWWPostRootContentQuery
     X-CSRFToken: <csrftoken>
     X-FB-LSD: <lsd_token>
     X-Requested-With: XMLHttpRequest
     Referer: https://www.instagram.com/p/{shortcode}/
     Content-Type: application/x-www-form-urlencoded
     ```
   - **Post Data**:
     ```text
     lsd=<lsd_token>&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery&server_timestamps=true&variables={"media_id":"{media_id}"}&doc_id=27130156389949648
     ```
   - **Payload Extraction**:
     `data.xig_polaris_media.if_not_gated_logged_out` -> extracts `video_versions` / `carousel_media`.
3. **HTML Relay Prefetched Fallback**:
   If GraphQL yields empty data, fetch `https://www.instagram.com/p/{shortcode}/` and parse `data-sjs` / JSON script tags for:
   `RelayPrefetchedStreamCache` -> `__bbox.result.data.xig_polaris_media.if_not_gated_logged_out`.

##### C. Error Taxonomy Correction
- Upstream `yt-dlp` returns: `"Requested content is not available, rate-limit reached or login required"`.
- `YtDlpErrorParser` previously misclassified any string with `rate-limit` as `RATE_LIMITED`.
- **Correction**: Only classify as `RATE_LIMITED` on confirmed HTTP 429, `Too Many Requests`, or unequivocal platform rate-limit codes. Ambiguous "rate-limit or login required" strings MUST map to `AUTH_REQUIRED` or `EXTRACTOR_FAILURE`, never confirmed rate-limiting.

---

### 2.2 Threads

#### Upstream References Inspected
1. **`boneless3vil/Downstream-AV` (`yt_dlp_plugins/extractor/threads.py`)** (MIT / Unlicense)
   - Default branch: `main` (active 2026)
   - Relevant routines: `_shortcode_to_pk`, `_find_embedded_post`, `_fetch_post_via_api`, `_parse_post`, `_logged_in`
2. **`tribixbite/yt-dlp-threads`** (Unlicense / public domain, currently bundled baseline in repo)
   - Pinned commit: `c4c44141cb10715f94296a808f5d89a0d24dfe94`
3. **`2Xsave/trsave`** (MIT)
   - Pinned commit: `2841945254c24c1ad3f3658410788503bcdced72`

#### Reference Flow Contracts

##### A. Shortcode to Numeric PK Derivation
Threads shortcodes use base-64 URL-safe alphabet:
`ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_`
Algorithm:
```kotlin
fun shortcodeToPk(shortcode: String): String {
    var pk = java.math.BigInteger.ZERO
    val base = java.math.BigInteger.valueOf(64)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    for (ch in shortcode) {
        val index = alphabet.indexOf(ch)
        if (index < 0) throw IllegalArgumentException("Invalid character in shortcode: $ch")
        pk = pk.multiply(base).add(java.math.BigInteger.valueOf(index.toLong()))
    }
    return pk.toString()
}
```

##### B. Anonymous Path (`BarcelonaPostPageContentQuery`)
Public Threads pages are often empty JS shells without server-rendered JSON.
1. `GET https://www.threads.net/@{user}/post/{shortcode}`:
   - Extract `LSD` token from `["LSD",[],{"token":"<lsd>"}]`.
   - Acquire `csrftoken` from cookie jar.
2. `POST https://www.threads.net/api/graphql` (or `https://www.threads.com/api/graphql`):
   - **Headers**:
     ```http
     User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/125.0.0.0 Safari/537.36
     Content-Type: application/x-www-form-urlencoded
     X-FB-LSD: <lsd>
     X-IG-App-ID: 238260118697367
     X-ASBD-ID: 129477
     X-FB-Friendly-Name: BarcelonaPostPageContentQuery
     Origin: https://www.threads.net
     Referer: https://www.threads.net/@{user}/post/{shortcode}
     Accept: */*
     Sec-Fetch-Site: same-origin
     Sec-Fetch-Mode: cors
     Sec-Fetch-Dest: empty
     ```
   - **Post Data**:
     ```text
     av=0&__user=0&__a=1&__req=1&dpr=1&lsd=<lsd>&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=BarcelonaPostPageContentQuery&variables={"postID":"<pk>"}&server_timestamps=true&doc_id=25460088156920903
     ```
   - **Response Traversal & Strict Target Isolation**:
     Threads responses contain the thread, replies, and recommendations:
     `data.data.edges[*].node.thread_items[*].post`
     **Non-negotiable Gate**: A post item is only accepted if:
     `post.code == shortcode || post.pk.toString() == pk`
     If neither matches, do NOT take the first video on the page; return `TARGET_NOT_IN_PAGE_DATA`.

##### C. Authenticated Path (Embedded Relay Data)
- When authenticated browser cookies (`sessionid`, `ds_user_id`, `csrftoken`) are present, Threads serves HTML containing the post's embedded Relay data in `<script type="application/json">` blocks.
- The authenticated API form of `BarcelonaPostPageContentQuery` has intermittent execution errors on Meta's backend; the reference proves that reading embedded Relay JSON directly from the authenticated HTML response is the most reliable path.
- Handles quoted posts and reposts (`text_post_app_info.share_info.quoted_post`, `quoted_attachment_post`, `reposted_post`) which are invisible to anonymous sessions.

---

### 2.3 X / Twitter

#### Upstream References Inspected
1. **`yt-dlp/yt-dlp` (`yt_dlp/extractor/twitter.py`)** (Unlicense)
   - Relevant routines: `_graphql_to_legacy`, `_call_api`, `is_logged_in`, `_fetch_guest_token`
2. **`TheFunny/TelegramTwitterMediaBot` (`crates/x-media/src/site/twitter/auth.rs`)** (MIT)
   - Relevant routines: `fetch`, `parse_tweet_result`, `variables`, `features`
3. **`medialab/minet` (`minet/twitter/api_scraper.py`)** (GPL-3.0, architectural study)
4. **`2Xsave/twsave`** (MIT)
   - Pinned commit: `0da4dd7db8a0f93445338821309cff736c79b9ec`

#### Reference Flow Contracts

##### A. Anonymous Guest Path (`TweetResultByRestId`)
- Standard Bearer Token:
  `AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA`
- Guest token obtained via `POST https://api.x.com/1.1/guest/activate.json`.
- Endpoint: `GET https://api.x.com/graphql/kLXoXTloWpv9d2FSXRg-Tg/TweetResultByRestId` (or current query ID).
- Result wrapping:
  - `__typename == "Tweet"` -> parse legacy media.
  - `__typename == "TweetWithVisibilityResults"` -> unwrap `tweetResult.result.tweet`.

##### B. Tombstone & Unavailable Parsing (NSFW / Adult Gate)
In CI #37 real-device testing, owner confirmed failing tweets were adult content. The API returned:
- `__typename == "TweetTombstone"` with `result.tombstone.text.text` containing:
  `"Age-restricted adult content. This content might not be appropriate for people under 18 years old. To view this media, you’ll need to log in to X."`
- Or `__typename == "TweetUnavailable"` with `result.reason`:
  - `NsfwLoggedOut`
  - `NsfwViewerHasNoStatedAge`
  - `Protected`
- **Correction**: Do not treat these as transient failures or attempt infinite guest-token refresh. Explicitly parse and classify these into `PlatformErrorCode.LOGIN_REQUIRED` / `PlatformErrorCode.AGE_RESTRICTED`.

##### C. Authenticated Path
When an X session is configured (`auth_token` and `ct0` cookies):
- **Request Headers**:
  ```http
  Authorization: Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA
  x-csrf-token: <ct0>
  x-twitter-auth-type: OAuth2Session
  x-twitter-client-language: en
  x-twitter-active-user: yes
  Cookie: auth_token=<auth_token>; ct0=<ct0>
  ```
- **Execution**:
  Call `TweetResultByRestId` or `TweetDetail` (`_8aYOgEDz35BrBcBal1-_w`). When called with an adult authenticated account, X returns the complete `Tweet` object with `legacy.extended_entities.media` instead of a Tombstone.
- **Session Validation & Expired Handling**:
  If response is 401 or 403 (with CSRF error or redirect), mark session status as `EXPIRED` and prompt user to re-import session.

---

## 3. Current Android Implementation Gaps

| Feature Area | Current Android State | Required Reference-Aligned State | Gap Severity |
| :--- | :--- | :--- | :--- |
| **Authentication Architecture** | Only `AnonymousSessionProvider` returning empty cookies; no persistence or UI | `PlatformSessionProvider` with secure Keystore storage, cookie domain isolation, status tracking, import/clear UX | **Critical Blocker** |
| **yt-dlp Session Handoff** | yt-dlp fallback runs purely anonymously even if native engine had auth session | Export platform cookies to private temporary Netscape cookie file in `cacheDir`, pass `--cookies`, delete securely in `finally` | **Critical Blocker** |
| **Instagram Anonymous** | Only scrapes HTML with Regex/data-sjs; misses empty shell pages | Primary path: `get_ruling_for_content` + `PolarisLoggedOutDesktopWWWPostRootContentQuery` GraphQL; fallback to Relay HTML | **High** |
| **Instagram Authenticated** | None | Adapt `/api/v1/media/{media_id}/info/` with `sessionid` | **High** |
| **Instagram Rate Limit** | False positive on "rate-limit reached or login required" | Ambiguous error mapped to `AUTH_REQUIRED` / `EXTRACTOR_FAILURE`; only true HTTP 429 is `RATE_LIMITED` | **High** |
| **Threads Anonymous** | Only scans HTML for JSON scripts; fails when page is empty JS shell | Convert shortcode -> numeric `pk`; query `BarcelonaPostPageContentQuery` GraphQL; strict target matching | **Critical** |
| **Threads Authenticated** | Bundled yt-dlp plugin explicitly notes lack of auth; native engine has no auth | Extract post from embedded Relay JSON in authenticated HTML response; support quote/repost resolution | **High** |
| **X Adult / NSFW Content** | Fails on `TweetTombstone` / `TweetUnavailable`; falls back to HTML and fails | Parse Tombstone text and `reason` (`NsfwLoggedOut`, `NsfwViewerHasNoStatedAge`); trigger authenticated fallback | **Critical** |
| **X Authenticated** | None | Authenticated GraphQL with `auth_token`, `ct0`, `x-twitter-auth-type: OAuth2Session` | **High** |

---

## 4. Authentication & Security Specifications

### 4.1 Storage & Privacy Requirements
- **Hardware-backed security**: Credentials stored in `EncryptedSharedPreferences` (backed by Android Keystore MasterKeys) or an AES-256-GCM encrypted private store.
- **Zero leakage guarantee**:
  - Never log raw cookie strings or token values.
  - Redaction filter in `PlatformHttpSession` and `YtDlpErrorParser` must sanitize `auth_token`, `ct0`, `sessionid`, `ds_user_id`, `csrftoken`, and `cookie` headers.
  - Settings UI only displays session state (`NOT_CONFIGURED`, `ACTIVE`, `EXPIRED`), username/ID if available, and last validated timestamp. Never display raw cookie text after import.
  - Prohibit committing fixtures, test cases, or configs containing real session tokens.

### 4.2 Platform Cookie Domain Isolation
Cookies must be strictly segregated by platform:
- **Meta (Instagram)**: `.instagram.com`, `i.instagram.com`, `www.instagram.com`
- **Meta (Threads)**: `.threads.net`, `.threads.com`, `www.threads.net`, `www.threads.com`
- **X (Twitter)**: `.x.com`, `.twitter.com`, `api.x.com`

Meta cookies must never be dispatched to X endpoints, and X cookies must never be dispatched to Meta endpoints.

### 4.3 yt-dlp Private Cookie Handoff
When invoking `YtDlpDownloadEngine` with an active authenticated session:
1. Format cookies into Netscape HTTP Cookie File format:
   ```text
   # Netscape HTTP Cookie File
   .instagram.com	TRUE	/	TRUE	0	sessionid	...
   ```
2. Write file to `context.cacheDir/ytdlp_sessions/<platform>_cookies_<uuid>.txt`.
3. Set file permissions to owner read/write only.
4. Pass `--cookies <temp_file_path>` to yt-dlp execution args.
5. In `finally` block: overwrite with zeroes and delete file immediately.

---

## 5. Licensing & Compliance Matrix

| Reference | License | Usage in this Project | Compliance Requirements |
| :--- | :--- | :--- | :--- |
| `yt-dlp/yt-dlp` | The Unlicense / Public Domain | Extractor algorithms, GraphQL queries, endpoint structures | Unlicense / public domain permissions; document upstream attribution |
| `2Xsave/insave, trsave, twsave` | MIT | Request profiles, URL normalization, parsing structures | Retain MIT copyright attribution in `THIRD_PARTY_NOTICES.md` |
| `boneless3vil/Downstream-AV` | MIT | Threads GraphQL query, shortcode-to-pk derivation, header composition | Port logic with attribution in notices |
| `TheFunny/TelegramTwitterMediaBot` | MIT | X authenticated fallback headers, query parameters | Port logic with attribution in notices |
| `Orang-Studio/InstaDownload` | GPL-3.0 | Architectural & behavioral research only | **DO NOT COPY CODE**. Independent implementation only |
| `deniscerri/ytdlnis` | GPL-3.0 | Architectural & behavioral research only | **DO NOT COPY CODE**. Independent implementation only |
| `JunkFood02/Seal` | GPL-3.0 | Architectural & behavioral research only | **DO NOT COPY CODE**. Independent implementation only |
| `medialab/minet` | GPL-3.0 | Architectural & behavioral research only | **DO NOT COPY CODE**. Independent implementation only |

---

## 6. Implementation Strategy & Phase Sequencing

As mandated by `HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`:

```text
Phase A: Reference Research & Documentation (This Document) -> COMMIT
    ↓
Phase B: Shared Authentication Infrastructure
    - Secure credential store (Keystore-backed)
    - Authenticated PlatformSessionProvider
    - Cookie parsing and domain isolation
    - Session status tracking & validation
    - Settings UI (import / validate / clear)
    - yt-dlp secure temporary cookie handoff
    - Log redaction & test verification
    ↓
Phase C: X First (Adult/NSFW Real-Device Regression)
    - Parse TweetTombstone & TweetUnavailable reason
    - Classify NSFW/Age restriction as LOGIN_REQUIRED / AGE_RESTRICTED
    - Authenticated GraphQL fallback using auth_token & ct0
    - Unit tests with mock fixtures
    ↓
Phase D: Instagram Implementation
    - Anonymous Polaris GraphQL (PolarisLoggedOutDesktopWWWPostRootContentQuery)
    - Authenticated /media/{id}/info/ path
    - Correction of ambiguous rate-limit error classification
    - Unit tests with mock fixtures
    ↓
Phase E: Threads Implementation
    - Shortcode <-> numeric PK conversion
    - BarcelonaPostPageContentQuery anonymous GraphQL
    - Strict target isolation verification
    - Authenticated embedded Relay fallback for quote/repost
    - Unit tests with mock fixtures
    ↓
Phase F: Full Regression, Build & CI Verification
    - ./gradlew test, lintDebug, assembleDebug
    - Push to PR #2 branch for CI build and APK artifact generation
```

---

## 7. Deliberate Deviations & Non-Copied Behaviors

1. **No Password / Automation Login**:
   We deliberately do NOT implement automated username/password entry, 2FA prompt handling, or CAPTCHA solving. Users manually import cookies via Settings UI.
2. **No Cobalt / Server Backend**:
   All extraction and downloads remain strictly on-device. No remote extraction server is used.
3. **No Unrelated Media Fallback on Threads**:
   Unlike simple scrapers that return the first video found in HTML, Threads extraction will fail safely if target ID (`code` or `pk`) is not confirmed, guaranteeing zero target pollution.
4. **No Permanent Cookie Files**:
   yt-dlp cookie files are ephemeral, private to `cacheDir`, and securely wiped on task completion.
