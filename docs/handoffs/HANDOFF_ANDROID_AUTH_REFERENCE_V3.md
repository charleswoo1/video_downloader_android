# HANDOFF — Android Extractor V3 / Authentication Foundation / Reference-First Recovery

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Status: **implementation contract / next-stage recovery task / NOT merge, tag, or release authorization**

This handoff **supersedes `docs/handoffs/HANDOFF_ANDROID_V0.1.0.md` wherever the two conflict**, especially the earlier rule that prohibited login/cookie support. Real-device CI #37 evidence plus current GitHub reference implementations now show that Instagram, Threads, and X all have legitimate cases where anonymous extraction is insufficient.

---

## 0. Mission

Move the Android downloader from repeated platform-specific blind patches to a durable extraction architecture:

```text
URL / Android share intent
        ↓
normalize + detect platform
        ↓
reference-aligned anonymous extractor
        ↓
success ───────────────→ download
        │
        └─ auth-required / gated / restricted
                    ↓
          authenticated session available?
              │                 │
             YES               NO
              │                 │
              ↓                 ↓
     authenticated fallback   clear UI prompt
              ↓
            download
```

Scope of this stage:

- shared authentication/session foundation;
- Instagram anonymous extractor alignment + authenticated fallback;
- Threads anonymous GraphQL path + authenticated fallback;
- X/Twitter adult/age-restricted authenticated fallback;
- yt-dlp cookie/session handoff;
- error taxonomy and safe diagnostics;
- deterministic tests and CI artifact.

Do not broaden this task into YouTube/TikTok/Facebook rewrites, account-cloud sync, password automation, or release publishing.

---

# 1. Hard rule: Reference-First Gate

**Do not blind-patch Instagram, Threads, or X.**

Before changing extractor logic for any of these platforms, the agent MUST:

1. Search current GitHub/upstream implementations.
2. Inspect current upstream `yt-dlp` where applicable.
3. Inspect at least one actively relevant platform-specific extractor/downloader implementation.
4. Compare the reference flow against this repository.
5. Document:
   - reference repository/file;
   - relevant function/endpoint/query;
   - authentication assumptions;
   - current project gap;
   - implementation chosen;
   - any deliberate deviation and why.
6. Prefer port/adaptation of a proven flow over inventing another parser.
7. Only add custom diagnostics/research when current references do not explain the real-device failure.

The required workflow is:

```text
GitHub reference
→ implementation comparison
→ port/adapt
→ local test/lint/assemble
→ push feature branch
→ ChatGPT review
   ├─ blockers found → fix locally, push, review again; DO NOT run GitHub CI
   └─ no blocking findings → manually trigger GitHub Actions CI
→ CI artifact
→ owner real-device verification
```

Not:

```text
failure
→ guess
→ regex/retry/UA patch
→ rebuild
```

Any extractor change without documented reference comparison is incomplete.

---

# 2. Current repository / workflow state

Continue the existing work:

```text
Repository: charleswoo1/video_downloader_android
PR: #2
Branch: feat/android-v0.1.0-initial
```

Before coding:

- read `AGENTS.md`;
- read `README.md`;
- read this handoff;
- inspect PR #2 and current branch head;
- inspect current `data/download/` implementation;
- inspect current bundled yt-dlp plugins;
- confirm CI workflow and artifact behavior.

Do not:

- modify `main` directly;
- create a new PR unless PR #2 becomes unusable;
- merge PR #2;
- create a tag;
- publish a Release.

After every implementation/review-fix round, run locally:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Then push the feature branch for review.

**Do not trigger GitHub Actions CI while review blockers remain.**

Current CI policy:

```yaml
on:
  workflow_dispatch:
```

Therefore:

- feature/PR branch pushes do **not** run CI automatically;
- `main` pushes also do **not** run CI automatically;
- local Gradle gates are mandatory on every implementation/review-fix round;
- after ChatGPT review reports **no blocking findings**, manually run **Actions → Android CI → Run workflow** and select the reviewed feature branch;
- the manually triggered CI run is the only point where a new owner-device APK artifact is required;
- after eventual merge to `main`, any desired integration CI must also be manually dispatched unless this policy is explicitly changed later.

---

# 3. Real-device evidence from CI #37

Treat this as regression evidence, not speculation.

## 3.1 Instagram

Known failure pattern:

```text
HTTP 200
body_size >100KB
restriction=NONE
```

Observed variants include:

```text
target raw=true
decoded=true
wrapper=true
media_node=false
stage=WRAPPER_SEEN_NO_MEDIA
```

and:

```text
target raw=true
decoded=true
wrapper=false
media_node=false
stage=TARGET_NOT_IN_PAGE
```

yt-dlp fallback can return a message equivalent to:

```text
Requested content is not available,
rate-limit reached or login required
```

Current `YtDlpErrorParser` classifies this as `RATE_LIMITED` merely because the string contains `rate-limit`.

That is incorrect.

### Required correction

Only classify as `RATE_LIMITED` on unambiguous evidence, such as:

- HTTP 429;
- `Too Many Requests`;
- a platform-specific documented rate-limit code/marker that is not ambiguous.

A message containing alternatives such as "rate-limit reached or login required" MUST NOT be classified as confirmed rate limiting.

---

## 3.2 Threads

Known real-device pattern:

```text
/share/... → canonical resolution succeeds
HTTP 200
body_size >100KB
raw_code=true
decoded_code=true
wrapper=false
media_node=false
matched_keys=none
stage=NO_TARGET_FOUND
```

The current parser is too dependent on finding the target in the expected embedded HTML/JSON shape, especially `code == targetCode`.

Do not solve this by simply adding more deep-regex variants.

---

## 3.3 X / Twitter

Two owner-confirmed failing examples were both adult/age-restricted content.

Observed:

```text
TweetResultByRestId
HTTP 200
outer_typename=TweetTombstone
effective_typename=TweetTombstone
target_rest_id=false
media_count=0
```

Guest-token refresh and retry still returns `TweetTombstone`.

HTML fallback shows:

```text
HTTP 200
target_in_html=true
initial_state_present=false
```

The owner confirmed both failing tweets are adult content.

This is now a first-class authentication case, not merely a parser mystery.

---

# 4. Existing architecture to preserve and extend

The repository already contains the correct boundary direction:

```kotlin
interface PlatformSessionProvider {
    fun cookiesFor(platform: Platform): List<Cookie>
    fun hasAuthenticatedSession(platform: Platform): Boolean
}
```

and currently has:

- `AnonymousSessionProvider`;
- `PlatformCookieJar`;
- `PlatformHttpSession`.

Do not replace these with an unrelated parallel auth system.

Extend this boundary into an authenticated implementation.

Recommended responsibilities:

```text
PlatformSessionProvider
├── AnonymousSessionProvider
└── AuthenticatedPlatformSessionProvider

PlatformCredentialStore
PlatformSessionStatus
PlatformSessionValidator
```

Names may differ if the current architecture suggests a better fit, but responsibilities must remain separated.

---

# 5. Authentication foundation requirements

## 5.1 Secure storage

Credential/session values must only live on the device.

Use Android Keystore-backed encrypted storage or an existing equivalent secure-storage abstraction if already present.

Never put credential values in:

- source code;
- BuildConfig;
- strings.xml;
- fixtures;
- GitHub;
- CI variables for end-user accounts;
- logs;
- screenshots/diagnostics;
- crash messages.

## 5.2 No password automation

Do NOT build username/password automatic login in this stage.

Do NOT build challenge/2FA/CAPTCHA automation.

MVP authentication input is a user-provided authenticated session/cookie set.

## 5.3 MVP Settings UX

Add a minimal settings area such as:

```text
Platform Sessions

Instagram / Threads
[ Not configured / Connected / Expired ]

X
[ Not configured / Connected / Expired ]
```

Required actions:

- Import / paste session cookies;
- Validate session;
- Clear session.

After import:

- never redisplay the full cookie values;
- show only status;
- never log raw credentials.

Do not expand this stage into a complex WebView login flow unless a current, safe reference implementation makes it clearly smaller and more robust. If considering WebView login, stop and document the proposal first.

## 5.4 Cookie isolation

Credentials MUST be platform/domain scoped.

Examples:

```text
Instagram → instagram.com / i.instagram.com
Threads   → threads.com / threads.net
X         → x.com / twitter.com / api.x.com as required
```

Never send Meta cookies to X or X credentials to Meta.

---

# 6. Instagram — required reference and implementation

## 6.1 Mandatory upstream reference

Inspect current:

```text
yt-dlp/yt-dlp
yt_dlp/extractor/instagram.py
```

Do not assume older yt-dlp behavior.

Current upstream separates authenticated and anonymous flows.

## 6.2 Authenticated Instagram path

Current yt-dlp detects an authenticated Instagram session through the `sessionid` cookie and uses a media-info API path equivalent to:

```text
/api/v1/media/{media_id}/info/
→ items[0]
→ media/video_versions
```

The Android implementation should adapt this behavior rather than inventing a different private-cookie request flow.

At minimum, research the actual required cookies/headers from the current reference. Likely relevant session values include:

```text
sessionid
csrftoken
ds_user_id
```

Only require additional Meta cookies if the reference proves they are needed.

## 6.3 Anonymous Instagram path

Current upstream anonymous extraction includes the Polaris logged-out GraphQL path:

```text
get_ruling_for_content
        ↓
PolarisLoggedOutDesktopWWWPostRootContentQuery
        ↓
/api/graphql
        ↓
data.xig_polaris_media
        ↓
if_not_gated_logged_out
```

and HTML/Relay fallback through a structure equivalent to:

```text
RelayPrefetchedStreamCache
→ __bbox
→ result.data.xig_polaris_media
→ if_not_gated_logged_out
```

The current Android engine is still centered around older/other structures such as:

```text
xdt_api__v1__media__shortcode__web_info
xdt_api__v1__clips...
data.media
data-sjs / generic deep walk
```

### Required change

Make the current upstream Polaris/`xig_polaris_media` path the primary anonymous Instagram reference path.

The existing parser may remain as a lower-priority fallback if still useful.

Do not keep extending old parser regexes as the primary strategy.

## 6.4 Instagram auth-required cases

The implementation must distinguish legitimate auth-required cases, including where supported by the current reference:

- restricted video;
- private/follower-only content;
- anonymous access gated by Instagram;
- anonymous post access redirected to login;
- session expired / invalid.

Anonymous mode should remain preferred for ordinary public content.

## 6.5 Instagram error classification

Fix the current ambiguous "rate-limit reached or login required" misclassification.

Required categories must distinguish at least:

```text
RATE_LIMITED
AUTH_REQUIRED
SESSION_EXPIRED
PRIVATE_CONTENT
CONTENT_UNAVAILABLE
PARSE_ERROR / PLATFORM_CHANGED
NETWORK_ERROR
NO_VIDEO
```

Use existing enums if suitable; extend them if necessary.

---

# 7. Threads — required reference and implementation

## 7.1 Mandatory reference

At minimum inspect:

```text
boneless3vil/Downstream-AV
yt_dlp_plugins/extractor/threads.py
```

Also search for current Threads extractors before coding and compare maintenance/date/approach.

Mainline yt-dlp does not currently provide the same complete Threads path, so platform-specific references matter.

## 7.2 Anonymous Threads path

The reference demonstrates that current Threads post pages can be JS shells and that reliable anonymous extraction can use:

```text
GET post page
        ↓
obtain csrftoken / LSD context
        ↓
shortcode → numeric post ID
        ↓
POST GraphQL
BarcelonaPostPageContentQuery
        ↓
target post JSON
        ↓
video_versions / carousel_media / DASH as applicable
```

Research the current request contract:

- current GraphQL endpoint;
- current `doc_id`;
- LSD/CSRF behavior;
- `X-IG-App-ID`;
- friendly-name header;
- Origin/Referer;
- required browser headers.

Do not blindly hard-code a query ID without documenting its source and refresh strategy.

Centralize any rotating GraphQL query identifier.

## 7.3 Target isolation is non-negotiable

Threads pages/results may contain:

- target post;
- replies;
- recommendations;
- quotes;
- reposts;
- unrelated videos.

Never improve apparent success rate by selecting the first available video.

Target identity must be proven by:

```text
code == target shortcode
or
pk == target numeric post ID
```

If target identity cannot be proven, fail safely.

## 7.4 Threads authenticated path

Current references show cases where logged-in session data is necessary or significantly more complete, especially:

- login-walled posts;
- quote posts;
- reposts;
- original post wrapped by a quote/repost.

Research the current authenticated Relay/page behavior and adapt it.

Likely relevant Meta session cookies include:

```text
sessionid
csrftoken
ds_user_id
```

Do not assume anonymous GraphQL plus session cookies is always accepted; follow current reference behavior.

## 7.5 Current bundled Threads plugin

The existing bundled plugin explicitly documents that it has no cookie/auth support and that the crawler/data-sjs approach is fragile.

Therefore:

```text
current crawler/data-sjs method
→ low-priority fallback only
```

The primary implementation should be the current reference-aligned GraphQL/Relay strategy.

---

# 8. X / Twitter — required reference and implementation

## 8.1 Mandatory references

Inspect at least:

### Reference A

```text
yt-dlp/yt-dlp
yt_dlp/extractor/twitter.py
```

The current extractor explicitly handles adult-content cases that fail when logged out and reasons including:

```text
NsfwLoggedOut
NsfwViewerHasNoStatedAge
```

### Reference B

```text
TheFunny/TelegramTwitterMediaBot
crates/x-media/src/site/twitter/auth.rs
```

This implements an authenticated fallback for NSFW/age-restricted tweets.

### Reference C

```text
medialab/minet
minet/twitter/api_scraper.py
```

This provides another authenticated web-session request reference.

Search current revisions before adapting anything.

## 8.2 Tombstone/unavailable parsing

Do not stop at:

```text
__typename = TweetTombstone
```

Parse and safely classify:

```text
tombstone.text.text
TweetUnavailable.reason
```

Examples that must be recognized if returned:

```text
Age-restricted adult content
NsfwLoggedOut
NsfwViewerHasNoStatedAge
Protected
```

Do not map every Tombstone to adult content.

## 8.3 X authenticated session

Research the current reference contract for authenticated web requests.

Likely required values:

```text
auth_token
ct0
```

Authenticated requests require correct CSRF/session relationship, including:

```text
Cookie ct0 == x-csrf-token
x-twitter-auth-type: OAuth2Session
```

Do not use a guest token as a substitute for an authenticated session.

## 8.4 X routing

Recommended behavior:

```text
Guest TweetResultByRestId
        ↓
normal Tweet / visibility wrapper
        ↓
native extraction
```

If the response is auth-gated:

```text
TweetTombstone / TweetUnavailable
        ↓
parse reason
        ↓
NSFW / age / protected / login-required
        ↓
authenticated session exists?
        ├─ yes → authenticated GraphQL fallback
        └─ no  → AUTH_REQUIRED user message
```

Use the current appropriate GraphQL query from reference code; do not freeze stale query IDs without a refresh/update strategy.

---

# 9. yt-dlp session handoff

The app has native engines and yt-dlp fallbacks.

If an authenticated platform session exists, the fallback path must be able to use the same legitimate session.

Bad behavior:

```text
native authenticated request
→ fallback
→ yt-dlp silently returns to anonymous mode
```

Required:

- convert platform-scoped session values into a yt-dlp-consumable cookie source;
- if a temporary Netscape cookie file is required:
  - store it only in app-private storage;
  - never expose it through external storage;
  - delete it after use;
  - do not log its contents;
  - do not leak the path in user-facing errors;
  - preserve domain isolation.

Research youtubedl-android/embedded yt-dlp options rather than inventing unsupported flags.

---

# 10. Error model

Use app-owned structured failures rather than raw extractor strings.

Required semantic categories:

```text
RATE_LIMITED
AUTH_REQUIRED
SESSION_EXPIRED
PRIVATE_CONTENT
CONTENT_UNAVAILABLE
NO_VIDEO
PLATFORM_CHANGED / UNSUPPORTED_LAYOUT
PARSE_ERROR
NETWORK_ERROR
POST_PROCESSING_ERROR
STORAGE_ERROR
CANCELLED
```

Avoid collapsing unrelated states into `GENERAL_ERROR`.

Do not expose multiline raw yt-dlp stderr as the primary UI message.

Examples:

- X adult tweet without session → 「此 X 貼文需要登入後才能存取」
- expired session → 「登入狀態已失效，請重新匯入 Session」
- target parser mismatch → 「來源網站頁面格式已變更，解析器需要更新」
- confirmed 429 → 「來源網站暫時限制存取頻率，請稍後再試」

---

# 11. Diagnostics

Diagnostics should identify the path used, not dump secrets.

Examples:

### Instagram

```text
auth_mode=ANONYMOUS
extractor=POLARIS_GRAPHQL
target_found=true
media_found=true
```

or:

```text
auth_mode=AUTHENTICATED
extractor=MEDIA_INFO_API
```

### Threads

```text
auth_mode=ANONYMOUS
extractor=BARCELONA_GRAPHQL
target_code_match=true
target_pk_match=true
```

### X

```text
auth_mode=ANONYMOUS
typename=TweetUnavailable
reason=NsfwLoggedOut
auth_fallback=attempted
auth_result=success
```

Never log:

- sessionid value;
- csrftoken value;
- auth_token value;
- ct0 value;
- Cookie header;
- raw Authorization secrets;
- signed CDN query strings;
- full raw platform JSON containing credentials/session data.

Keep and expand the existing sanitizer tests.

---

# 12. Required implementation sequence

Do not make large three-platform changes simultaneously without checkpoints.

## Phase A — reference research

Create:

```text
docs/research/REFERENCE_RESEARCH_AUTH_EXTRACTORS.md
```

It must contain:

- Instagram references + exact flow;
- Threads references + exact flow;
- X references + exact flow;
- current Android implementation gaps;
- authentication requirements;
- licensing notes;
- selected implementation paths;
- deliberate non-copied behavior.

**Do not begin extractor coding until this file is committed.**

## Phase B — shared authentication infrastructure

Implement:

- secure credential storage;
- authenticated `PlatformSessionProvider`;
- cookie parsing/domain isolation;
- session status/validation;
- clear-session behavior;
- Settings MVP;
- yt-dlp session handoff;
- logging redaction tests.

Checkpoint and test before platform changes.

## Phase C — X first

Reason is already strongly evidenced by owner adult-content tests.

Implement:

- Tombstone/`TweetUnavailable` reason parsing;
- NSFW/age auth-required classification;
- authenticated X request path;
- expired-session behavior;
- tests.

## Phase D — Instagram

Implement:

- current Polaris anonymous GraphQL path;
- `xig_polaris_media`;
- Relay-prefetched fallback where current upstream does so;
- authenticated media-info path;
- fix ambiguous rate-limit classification;
- tests.

## Phase E — Threads

Implement:

- Barcelona post GraphQL anonymous path;
- shortcode ↔ numeric ID handling;
- strict target isolation;
- authenticated Relay/page fallback;
- quote/repost handling;
- tests.

## Phase F — full local regression + review gate + manual CI

Run locally:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Then:

1. push to the existing PR #2 branch;
2. request ChatGPT review;
3. if review finds blockers, return to implementation and repeat the local gates — **do not trigger GitHub CI**;
4. only after review reports **no blocking findings**, manually trigger `Android CI` via `workflow_dispatch` for the feature branch;
5. use that green manual CI run to produce the next APK artifact for owner-device testing.

---

# 13. Tests

CI must not depend on live third-party websites.

Use fixtures/mocked responses.

## 13.1 Authentication tests

Required:

- cookie parser;
- per-platform domain isolation;
- secure persistence abstraction;
- clear session;
- session status;
- expired session;
- secret redaction;
- yt-dlp cookie handoff cleanup.

## 13.2 Instagram tests

Required fixtures/coverage:

- anonymous Polaris success;
- `xig_polaris_media`;
- `if_not_gated_logged_out`;
- Relay-prefetched page fallback;
- restricted → auth required;
- private/follower-only → auth required;
- explicit HTTP 429 → rate limited;
- ambiguous "rate-limit or login required" → NOT confirmed rate limited;
- authenticated media-info success.

## 13.3 Threads tests

Required:

- Barcelona GraphQL success;
- target code match;
- target pk match;
- target not found while unrelated video exists → FAIL;
- carousel video;
- DASH if supported by current implementation;
- quote/repost;
- login-required path;
- authenticated session path;
- share URL normalization/resolution.

## 13.4 X tests

Required:

- normal Tweet;
- `TweetWithVisibilityResults`;
- `TweetTombstone` with age-restricted text;
- `TweetUnavailable reason=NsfwLoggedOut`;
- `TweetUnavailable reason=NsfwViewerHasNoStatedAge`;
- protected/login-required;
- authenticated fallback success;
- expired/invalid session;
- normal public guest success remains intact.

---

# 14. Owner real-device acceptance gate

CI green is NOT final acceptance.

The owner will test the generated APK.

Minimum test matrix:

## Instagram

- 2 previously successful public videos;
- 2 previously failing public videos;
- authenticated-only case when safely available.

## Threads

- 2 previously successful posts;
- 2 previously failing posts;
- quote/repost or login-gated case when available.

## X

- 1 normal public video;
- 2 known adult/age-restricted tweets that failed CI #37.

Expected X behavior:

```text
without X session
→ clear AUTH_REQUIRED message

with valid X session
→ authenticated extraction attempt
→ success if the logged-in account can view the tweet
```

Do not report Instagram/Threads/X as PASS until owner device verification.

---

# 15. Stop conditions

Stop and report evidence instead of guessing if:

1. A current reference flow no longer works.
2. GraphQL/query IDs have changed and no reliable current source/refresh strategy is found.
3. A platform requires an authentication mechanism beyond the approved session-cookie MVP.
4. Safe yt-dlp cookie handoff cannot be implemented with the current embedded runtime.
5. Secure storage integration conflicts with existing architecture.
6. Reference code licensing is incompatible with direct adaptation.
7. The only proposed solution is another speculative regex/UA/retry patch.
8. A target identity cannot be proven without risking downloading unrelated media.

When stopping, report:

- exact blocker;
- reference checked;
- sanitized response/error category;
- what was attempted;
- smallest next investigation.

---

# 16. Definition of done

Code-side completion requires all of the following:

## Research

- [ ] `docs/research/REFERENCE_RESEARCH_AUTH_EXTRACTORS.md` committed first.
- [ ] All three platforms document current GitHub references and implementation gaps.
- [ ] Any deviations from reference behavior are justified.

## Authentication

- [ ] Authenticated session provider exists.
- [ ] Credentials are securely stored on-device.
- [ ] Sessions can be validated and cleared.
- [ ] Platform cookies are isolated.
- [ ] Secrets are redacted from logs/diagnostics.
- [ ] Native and yt-dlp paths can use the same platform session where required.

## Instagram

- [ ] Polaris/`xig_polaris_media` anonymous path implemented.
- [ ] Authenticated media-info path implemented.
- [ ] Ambiguous rate-limit classification fixed.

## Threads

- [ ] Barcelona GraphQL anonymous path implemented.
- [ ] Strict target isolation preserved.
- [ ] Authenticated session path exists.
- [ ] Quote/repost behavior is handled or correctly classified as auth-required.

## X

- [ ] Tombstone/unavailable reasons are parsed.
- [ ] NSFW/age-restricted login requirement is classified.
- [ ] Authenticated fallback is implemented.

## Validation

- [ ] Local unit tests pass on every implementation/review-fix round.
- [ ] Local lint passes on every implementation/review-fix round.
- [ ] Local debug assemble passes on every implementation/review-fix round.
- [ ] Feature branch was reviewed with no blocking findings before CI was triggered.
- [ ] Manual GitHub Actions CI passes after the review gate.
- [ ] APK artifact is uploaded from that review-approved CI run.
- [ ] No merge/tag/release performed.
- [ ] Owner receives exact manual test plan.

---

# 17. Required Antigravity report

When the implementation is ready for owner testing, return:

```text
Reference research:
- Instagram:
- Threads:
- X:
- Deviations from references:

Authentication architecture:
- credential storage:
- session provider:
- session validation:
- yt-dlp handoff:
- secret redaction:

Instagram:
- anonymous path:
- auth path:
- error classification changes:

Threads:
- anonymous path:
- auth path:
- target-isolation behavior:

X:
- guest path:
- Tombstone/unavailable classification:
- auth path:

PR:
Branch:
Head commit:

Tests:
Local unit tests:
Local lint:
Local assembleDebug:
Review gate:
- blocking findings remaining: YES / NO
CI trigger:
- NOT RUN / MANUAL workflow_dispatch
CI run:
Artifact:

Regression status:
- YouTube:
- Facebook:
- Instagram: NEEDS OWNER DEVICE TEST / PASS / BLOCKED
- Threads: NEEDS OWNER DEVICE TEST / PASS / BLOCKED
- X: NEEDS OWNER DEVICE TEST / PASS / BLOCKED

Known limitations:
Merged: NO
Release created: NO
```

Do not report a platform as PASS merely because mocked tests or CI pass.

---

# 18. Final directive

**Reference-first is now a project-level engineering rule for Instagram, Threads, and X.**

Do not blind-patch these extractors. Search current GitHub/upstream implementations first, document the comparison, port/adapt the proven flow, preserve strict target isolation, add authentication as a first-class platform capability, and keep all credentials private to the user's device.

No merge, tag, or Release without explicit owner instruction.
