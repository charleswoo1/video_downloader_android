# HANDOFF — V3 Review Fixes Before Owner Device Test

Repository: `charleswoo1/video_downloader_android`

Target: existing PR #2 / branch `feat/android-v0.1.0-initial`

Reviewed head: `d97017e9d2fa2967ac4322b12159a1d18be2f34f`

Reviewed CI: Android CI Run #39 — SUCCESS

Artifact: `SocialVideoDownloader-Android-CI-v0.1.0-run-39`

Status: **review correction contract / NOT merge, tag, or release authorization**

Governing contract remains:

`docs/handoffs/HANDOFF_ANDROID_AUTH_REFERENCE_V3.md`

This file is a narrow follow-up. It does not authorize a new architecture. Fix the blockers below, rerun tests/CI, and return a new APK for owner-device testing.

---

## Review summary

The phase ordering was followed correctly:

- Phase A reference research was committed first.
- Phase B auth/session infrastructure was committed next.
- Phase C X auth work followed.
- Phase D Instagram work followed.
- Phase E Threads work followed.
- CI Run #39 is green.

However, the implementation does **not yet faithfully implement the request contracts documented in its own reference research** for Instagram and Threads, and there are two authentication-foundation correctness issues that can make valid browser-cookie imports fail or appear validated when they are not.

Do not start owner-device acceptance testing from Run #39. Fix these blockers first.

---

# BLOCKER 1 — Instagram Polaris request contract does not match the researched/current yt-dlp flow

## Current code

`NativeInstagramEngine.fetchPolarisLoggedOutGraphQL()` currently constructs approximately:

```text
POST https://www.instagram.com/api/graphql

doc_id=27130156389949648
variables={"shortcode":"<shortcode>"}
```

and sends headers such as:

```text
X-IG-App-ID
X-ASBD-ID
X-FB-Friendly-Name
Origin
Referer
```

but it does **not** perform the actual upstream bootstrap/request contract documented in:

`docs/research/REFERENCE_RESEARCH_AUTH_EXTRACTORS.md`

The research document itself correctly states that current yt-dlp uses:

1. `get_ruling_for_content`
2. bootstrap/obtain CSRF and LSD context
3. `PolarisLoggedOutDesktopWWWPostRootContentQuery`
4. GraphQL variables based on **numeric `media_id`**, not shortcode
5. `X-CSRFToken`
6. `X-FB-LSD`
7. form field `lsd`
8. `fb_api_caller_class=RelayModern`
9. `fb_api_req_friendly_name=PolarisLoggedOutDesktopWWWPostRootContentQuery`
10. `server_timestamps=true`

The current implementation omits several of these and sends the wrong variable shape.

## Why this blocks device testing

A mocked test that returns a valid Polaris JSON body will pass regardless of whether the real HTTP request is valid.

Therefore current unit tests prove response parsing, but **do not prove the real Polaris request can obtain that response**.

This directly undermines the purpose of Phase D.

## Required fix

Re-read the **current** `yt-dlp/yt-dlp/yt_dlp/extractor/instagram.py` before editing.

Port/adapt the actual current request contract, including:

- correct shortcode → numeric media ID conversion;
- current content-ruling preflight where upstream uses it;
- real CSRF acquisition/usage;
- real LSD acquisition/usage;
- correct GraphQL form fields;
- correct `variables={"media_id":"..."}` shape if still current upstream behavior;
- correct friendly-name/header contract;
- current `doc_id` centralized in one place.

Do not simply add hard-coded placeholder CSRF/LSD values.

## Tests

Add deterministic request-capture tests proving that the outgoing anonymous Polaris request contains the expected:

- media ID variable;
- `lsd` form field;
- `X-FB-LSD`;
- `X-CSRFToken` when present/required;
- friendly name;
- content-ruling behavior.

The test must fail if the implementation regresses to `variables={"shortcode":...}`.

---

# BLOCKER 2 — Threads Barcelona GraphQL sends an empty LSD and skips the documented bootstrap context

## Current code

`NativeThreadsEngine.fetchBarcelonaGraphQL()` currently builds:

```text
lsd=
...
doc_id=25460088156920903
```

and posts directly to:

```text
https://www.threads.net/api/graphql
```

without first obtaining the page/session bootstrap context documented by Phase A research.

It also currently omits important reference headers/context including the real LSD value and the researched `X-FB-LSD` request contract.

The Phase A research itself says the anonymous flow is:

```text
GET target post page
→ extract LSD
→ acquire csrftoken / page context
→ POST BarcelonaPostPageContentQuery
```

The implementation currently skips the first half and sends an empty LSD.

## Why this blocks device testing

Again, the tests mock the HTTP response and therefore validate only JSON traversal and target isolation.

They do not validate that Meta will accept the outgoing request.

A green Run #39 therefore cannot establish that the new Barcelona path is usable on-device.

## Required fix

Re-read the current reference:

`boneless3vil/Downstream-AV/yt_dlp_plugins/extractor/threads.py`

and search current Threads implementations again before editing.

Implement the real current anonymous bootstrap flow:

1. GET canonical target page with the required browser navigation headers.
2. Extract current LSD/page token from HTML.
3. obtain/use the relevant CSRF/session cookie context.
4. POST the GraphQL request with the current required form/header fields.
5. Include the current `X-FB-LSD`/friendly-name/App-ID/Origin/Referer/Sec-Fetch contract where the reference still requires it.
6. Centralize `doc_id` and document source/update strategy.

Do not send `lsd=` as an empty placeholder and call that the reference implementation.

## Target isolation

Preserve the current strict rule:

```text
post.code == target shortcode
OR
post.pk == target numeric pk
```

Never fall back to first/recommended media.

## Tests

Add request-capture tests that prove:

- page bootstrap GET happens before GraphQL POST;
- the extracted LSD is propagated into the request;
- required headers/form fields are populated;
- empty LSD cannot silently pass the test;
- strict target isolation still holds.

---

# BLOCKER 3 — Netscape cookie parser drops standard `#HttpOnly_` cookie lines

## Current code

`PlatformCookieParser.parseNetscape()` currently does:

```kotlin
if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) continue
```

Standard Netscape browser-cookie exports commonly encode HttpOnly cookies as lines beginning with:

```text
#HttpOnly_.x.com
#HttpOnly_.instagram.com
```

Those are **cookie records**, not comments.

Because the parser skips every `#...` line, a perfectly valid browser export can lose exactly the sensitive login cookies we need, including values such as `auth_token` or `sessionid`.

The current tests do not cover `#HttpOnly_`.

## Required fix

Before treating a line as a comment:

1. detect `#HttpOnly_`;
2. strip only the `#HttpOnly_` marker from the domain field;
3. parse the line as a normal Netscape record;
4. preserve `httpOnly=true` in the resulting OkHttp Cookie when practical;
5. continue ignoring ordinary comment lines.

## Tests

Add at minimum:

- X Netscape export with `#HttpOnly_.x.com ... auth_token ...`;
- Instagram Netscape export with `#HttpOnly_.instagram.com ... sessionid ...`;
- ordinary `# Netscape HTTP Cookie File` comment remains ignored;
- cross-platform domain isolation still works.

This is required before asking the owner to manually import cookies.

---

# BLOCKER 4 — Session state says ACTIVE before real validation; Threads “Validate” does not validate

## Current behavior

The `SessionState.ACTIVE` documentation says:

> Active authenticated session present and validated.

But import currently calls `saveCookies()`, which immediately stores:

```text
state = ACTIVE
```

before any network validation.

Additionally:

### Threads

`validateSession(Platform.THREADS)` currently only checks that a `sessionid` exists, then calls:

```text
markActive("已設定 Session")
```

There is no live validation.

### Instagram

Validation calls a content-ruling URL with `target_id=1` and accepts HTTP:

```text
200
400
404
```

as proof of a valid authenticated session.

That is not strong enough to prove the supplied session cookie is actually authenticated.

### Meta combined import

`importMetaSession()` stores both Instagram and Threads immediately, but the UI validates only the platform that opened the import dialog. The other platform can remain displayed/used as ACTIVE without ever being validated.

## Required fix

Make status semantics truthful.

Preferred solution:

Add an explicit state such as:

```text
UNVERIFIED / CONFIGURED
```

then use:

```text
import
→ CONFIGURED/UNVERIFIED
→ validate
→ ACTIVE or EXPIRED
```

If changing the enum is unnecessarily invasive, use an equivalent design, but **do not label unvalidated credentials ACTIVE**.

For each platform, validation must exercise a current authenticated endpoint/page flow that can distinguish a real logged-in session from anonymous behavior.

For Meta combined import:

- validate Instagram and Threads independently;
- do not imply one validates the other;
- if one is valid and the other is not, preserve/report their statuses independently.

A validation network/transient failure must not be mislabeled EXPIRED unless the platform actually rejects the credentials.

## Tests

Add tests proving:

- import alone is not “validated ACTIVE”;
- valid session → ACTIVE;
- explicit auth rejection → EXPIRED;
- transient/network validation failure does not falsely expire;
- Meta combined import can yield independent IG/Threads status;
- UI/status flow reflects those independent states.

---

# Non-blocking findings

## A. Phase sequencing is correct

The agent followed the requested order:

```text
998534f  Phase A research
d1a5a64  Phase B auth infrastructure
ff358a7  Phase C X
dbaf578  Phase D Instagram
d97017e  Phase E Threads
```

Keep this discipline.

## B. X direction is substantially better

The X code now:

- classifies `NsfwLoggedOut` / `NsfwViewerHasNoStatedAge`;
- detects age-restricted Tombstone text;
- attempts authenticated fallback when a session exists;
- keeps guest vs authenticated flows separate;
- marks 401/403 auth failure as expired.

No new X blocker was found in this review that is more urgent than the four items above.

## C. Third-party attribution

Phase A states that MIT reference adaptations require attribution in `THIRD_PARTY_NOTICES.md`, but the V3 implementation diff does not currently include that file.

Before merge/release, ensure the final code-level adaptations and required MIT notices are reflected in the repository's third-party attribution. This does not need to block the next debug APK if the four functional blockers above are fixed first.

---

# Required verification after fixes

Run:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Then push to the same PR #2 branch and wait for Android CI.

Return:

```text
Head commit:
CI run:
Artifact:
Artifact digest:

Blocker 1 Instagram Polaris:
- reference rechecked:
- request contract:
- tests:

Blocker 2 Threads Barcelona:
- reference rechecked:
- bootstrap/LSD contract:
- tests:

Blocker 3 HttpOnly Netscape:
- implementation:
- tests:

Blocker 4 session validation:
- state semantics:
- IG validation:
- Threads validation:
- X validation:
- tests:

Known limitations:
Merged: NO
Release created: NO
```

Do not merge/tag/release.

Do not report Instagram/Threads/X as owner-device PASS until the new APK is actually tested.
