# DESIGN / HANDOFF — Android WebView Login V1

Repository: `charleswoo1/video_downloader_android`

Design branch: `design/webview-login-v1`

Design base commit: `7c40c0ec7898c8af21cc24b6120cd501c47a54e5`

Current auth baseline:
- PR #2 is still open.
- Exact reviewed/CI-tested head: `7c40c0ec7898c8af21cc24b6120cd501c47a54e5`.
- Manual Android CI Run #42 passed unit tests, lint, assembleDebug, and artifact upload.
- Do **not** modify PR #2 for this feature; preserve the tested head.
- This document is design-only. Implementation should start from the post-merge auth baseline unless the owner explicitly authorizes a stacked branch.

Feature codename: **WebView Login V1**

Goal: let Android users establish Instagram / Threads / X authenticated sessions entirely on-device, without using a PC to export cookies, while preserving the existing anonymous-first extractor behavior and encrypted credential architecture.

---

## 1. Product goal

Current flow:

```text
Desktop browser
→ export cookies
→ transfer cookie text/file to phone
→ Android app manual import
→ validate session
```

Target flow:

```text
Android app
→ Platform Sessions
→ Login Instagram / Threads / X
→ visible in-app WebView
→ user signs in directly to the real platform page
→ Android WebView CookieManager exposes the resulting session cookies
→ app filters only the platform allowlisted cookies
→ existing encrypted PlatformCredentialStore
→ existing validateSession()
→ ACTIVE
```

Manual Cookie import must remain available as a fallback.

This feature does **not** read cookies from the installed Instagram / Threads / X native apps. Android app sandboxing does not permit that.

This feature does **not** replace Meta/X authentication with OAuth tokens. The downloader needs the web session cookies already consumed by the current authenticated extractor paths.

---

## 2. Reference-First Gate

Before implementation, recheck these exact current public references.

### Primary architecture reference

Repository:
`SerZhyAle/FastMediaSorter_mob_v2`

Reference head observed during design:
`65b0cd792f567c77164bc3d49aaceb8b68a7e5bf`

Relevant files:
- `app_v2/src/main/java/com/sza/fastmediasorter/data/link/auth/KnownAuthResources.kt`
- `app_v2/src/main/java/com/sza/fastmediasorter/ui/share/auth/WebViewAuthDialogFragment.kt`
- `docs/RECEIVING_LINKS-ru.md`

Useful behaviors:
- canonical login URL table for Instagram / Threads / X;
- visible WebView login;
- clear stale WebView cookies before fresh login;
- JavaScript + DOM storage;
- `CookieManager.getCookie(domain)`;
- custom-scheme / `intent://` handling;
- encrypted cookie persistence;
- cleanup of WebView cookies/cache/history after capture;
- optional account/session metadata patterns.

License observed:
**Apache-2.0**.

If implementation copies or adapts source expression from this repo rather than independently reimplementing the behavior, preserve required Apache attribution/notices.

### Instagram / Meta challenge reference

Repository:
`daveli07098/ig-image-downloader`

Reference head observed during design:
`51c889df22b1c9e8056088b6006295d3e9f8ce8e`

Relevant files:
- `lib/screens/login_screen.dart`
- `android/app/src/main/kotlin/.../MainActivity.kt`
- `lib/services/session_service.dart`

Useful behaviors:
- Instagram WebView login flow;
- native Android `CookieManager` access for HttpOnly `sessionid`;
- handling Instagram challenge / 2FA / risky-contact / redirect-loop states;
- avoiding native-app custom-scheme handoff when WebView session capture is required;
- optional Instagram-login → `threads.com` cookie side-probe;
- X login flow.

No root LICENSE file was detected during design review. Treat this repository as **behavioral/reference evidence only** unless licensing is independently clarified. Do not copy source code.

### Kotlin/Compose cookie-completion reference

Repository:
`777abhishek/Otter`

Reference head observed during design:
`21794a1ae3838aead85b394c1b5463794d84675b`

Relevant file:
- `app/src/main/kotlin/com/Otter/app/ui/screens/WebViewLoginScreen.kt`

Useful behaviors:
- native Kotlin/Compose `AndroidView(WebView)`;
- Instagram cookie-completion signal;
- X `auth_token` + `ct0` completion signal;
- fresh WebView cookie state.

License observed:
**GPL-3.0**.

Use it only as behavioral/reference evidence. Do **not** copy GPL source into this project unless the owner explicitly chooses GPL-compatible licensing for this project.

### Reference-use rule

Priority:
1. Current platform behavior / current extractor references.
2. Existing project V3 auth contracts.
3. Apache reference patterns where useful.
4. GPL / no-license projects only for behavioral corroboration.

Do not blindly clone another downloader's architecture.

---

## 3. Non-goals

WebView Login V1 must **not**:

- read another installed app's private storage;
- ask for the user's Instagram / Threads / X password outside the real platform WebView;
- intercept or log password form fields;
- inject JavaScript to capture credentials;
- use `addJavascriptInterface` for auth credential capture;
- upload cookies to any external service;
- put cookies in GitHub, CI logs, screenshots, diagnostics, crash reports, test fixtures, analytics, or clipboard;
- turn authenticated extraction into auth-first behavior;
- weaken V3's explicit auth-fallback gates;
- remove manual cookie import;
- add automatic GitHub CI triggers;
- implement multi-account support in V1.

---

## 4. UX design

Existing Platform Sessions cards remain.

### Not configured

Instagram card:

```text
Instagram                          未設定

[登入 Instagram]     [手動匯入 Cookie]
```

Threads:

```text
Threads                            未設定

[登入 Threads]        [手動匯入 Cookie]
```

X:

```text
X (Twitter)                        未設定

[登入 X]              [手動匯入 Cookie]
```

### Configured / Active

```text
Instagram                          已連線
憑證: N 個 Cookie (驗證成功)

[重新登入]   [驗證狀態]   [更新 Cookie]   [清除]
```

Manual import remains a secondary/advanced path.

### Login screen

Use a full-screen Compose destination rather than a small AlertDialog. Login pages, 2FA and security challenges need full vertical space and keyboard handling.

Top app bar:
- title: `登入 Instagram`, `登入 Threads`, `登入 X`;
- close/back action;
- optional status text: `等待登入`, `正在驗證`, `登入成功`.

Body:
- visible WebView;
- small privacy note:
  `帳號密碼直接輸入平台網頁；本 App 不讀取或儲存密碼，只在登入完成後保存必要的 Session Cookie。`

Do not display raw cookies.

### Success

After cookie candidate is found:

```text
Cookie candidate
→ import to encrypted store
→ validateSession(platform)
→ only ACTIVE counts as successful login
```

If validation succeeds:
- close WebView;
- return to Platform Sessions;
- show `已連線`.

If validation remains CONFIGURED:
- do not falsely show success;
- keep the login screen or present a recoverable `尚未完成驗證` state;
- allow retry/reload/manual fallback.

If validation reports EXPIRED / rejected:
- do not retain a false ACTIVE state;
- show platform-specific retry guidance.

---

## 5. Platform configuration

Create one declarative config model instead of hardcoding three screens.

Suggested shape:

```kotlin
data class PlatformWebLoginConfig(
    val platform: Platform,
    val loginUrl: String,
    val cookieProbeUrls: List<String>,
    val requiredCookieNames: Set<String>,
    val optionalCookieNames: Set<String>,
    val allowThirdPartyCookies: Boolean,
    val postLoginProbe: PostLoginProbe? = null
)
```

Suggested V1 table:

| Platform | Login URL | Probe URL(s) | Required cookies | Optional allowlist |
|---|---|---|---|---|
| Instagram | `https://www.instagram.com/accounts/login/` | `https://www.instagram.com/` | `sessionid` | `csrftoken`, `ds_user_id` |
| Threads | `https://www.threads.com/login` | `https://www.threads.com/`, legacy `https://www.threads.net/` only if current behavior requires it | `sessionid` | `csrftoken`, `ds_user_id` |
| X | `https://x.com/i/flow/login` | `https://x.com/`, `https://twitter.com/` fallback | `auth_token` + `ct0` | none in V1 |

The existing `PlatformCookieParser` and domain isolation remain authoritative for final import.

---

## 6. Cookie capture rules

### Source

Use Android:
`android.webkit.CookieManager.getInstance().getCookie(url)`

This native API is required because session cookies such as Instagram `sessionid` / X `auth_token` may be HttpOnly and are not available through JavaScript `document.cookie`.

### Allowlist

Never persist the entire WebView cookie jar by default.

Parse the returned raw header and retain only the configured allowlisted names for the current platform.

V1 minimum persistence:

Instagram:
- `sessionid`
- `csrftoken` if present
- `ds_user_id` if present

Threads:
- `sessionid`
- `csrftoken` if present
- `ds_user_id` if present

X:
- `auth_token`
- `ct0`

Do not persist ad/tracking cookies merely because the WebView produced them.

### Domain assignment

`CookieManager.getCookie()` returns a cookie header, not a full Netscape record with domain/path metadata.

For captured WebView cookies:
- assign the known platform default domain through the existing project parser/import path;
- do not infer cross-platform ownership from cookie name alone;
- keep Instagram, Threads and X storage domain-isolated.

### Import API

Do not route WebView login through UI text fields.

Prefer adding a typed provider method, e.g.:

```kotlin
fun importCapturedSession(
    platform: Platform,
    rawCookieHeader: String
): Result<PlatformSessionInfo>
```

or:

```kotlin
fun importCapturedCookies(
    platform: Platform,
    cookies: List<Cookie>
): Result<PlatformSessionInfo>
```

It should reuse the same mandatory-cookie checks as manual import.

Avoid creating a second credential store or parallel auth stack.

---

## 7. Login completion criteria

A URL redirect alone is not sufficient.

### Instagram

Candidate completion:
- current page is within Instagram web origin, AND
- native CookieManager contains nonblank `sessionid`.

Then:
- capture allowlisted cookies;
- import as CONFIGURED;
- call existing Instagram `validateSession()`;
- ACTIVE requires existing structured validation:
  - HTTP success;
  - JSON `status == ok`;
  - user object;
  - credible positive numeric `pk`.

Do not mark ACTIVE because `sessionid` merely exists.

Support normal login intermediate paths without treating them as success:
- `/accounts/login/`
- `/accounts/onetap/`
- `/accounts/password/`
- `/challenge/`
- `/two_factor`
- `/verify/`
- integrity / risky-contact/security challenge paths.

Cookie presence + existing validation is the final authority.

### Threads

Candidate completion:
- Threads origin;
- nonblank `sessionid`.

Then existing validation must prove:
- DTSG evidence, AND
- documented page user ID or valid `ds_user_id` cookie.

Do not mark ACTIVE from an HTTP 200 page alone.

### X

Candidate completion requires both:
- `auth_token`
- `ct0`

Then call existing X validation.

Never fabricate `ct0`.

---

## 8. Instagram → Threads side-probe

After **Instagram validation succeeds** and before WebView cookies are scrubbed:

1. Query native CookieManager for:
   `https://www.threads.com/`
2. If no Threads `sessionid`: do nothing.
3. If a Threads session candidate exists:
   - capture only the Threads allowlist;
   - if Threads is NOT_CONFIGURED or EXPIRED, import candidate and validate Threads;
   - if Threads is already ACTIVE, do not silently overwrite it in V1.
4. Failure to obtain/validate Threads must not make Instagram login fail.
5. Do not navigate the WebView to Threads merely to manufacture a cookie.
6. Log only a boolean/result class, never cookie values.

This is an opportunistic convenience, not a guaranteed Meta behavior.

---

## 9. WebView isolation and lifecycle

WebView's CookieManager is app-process-global, so V1 permits only one active login flow at a time.

Before a **fresh login**:
- `CookieManager.removeAllCookies(...)`;
- `flush()`;
- clear WebView cache/history/form data;
- wait for cookie-removal callback before loading the login URL.

This cleanup affects only this app's WebView cookie store, not Chrome or the native social apps.

After successful capture, cancellation, or terminal failure:
- scrub WebView cookies;
- clear cache/history/form data;
- destroy the WebView when leaving the screen.

Important ordering for Instagram:
- capture Instagram cookies;
- validate/store Instagram;
- perform optional Threads side-probe;
- only then scrub WebView cookies.

Do not scrub before the Threads side-probe.

---

## 10. WebView settings

Required baseline:
- JavaScript enabled;
- DOM storage enabled;
- cookies enabled;
- third-party cookies enabled where the platform login flow needs them;
- normal HTTP/HTTPS navigation allowed;
- non-web schemes blocked by default.

### Custom schemes / `intent://`

Login flows may try to hand off to the installed native app.

For V1, remain in WebView because native-app handoff prevents WebView cookie capture.

Behavior:
- allow `http`, `https`, and safe `about:` internal use;
- for `intent://`, parse `browser_fallback_url` if present and load that HTTP(S) URL;
- block other schemes unless explicitly reviewed;
- do not launch arbitrary external intents from the login WebView.

---

## 11. User-Agent policy

Do not hardcode another project's exact UA string.

V1 design:
- derive a current Android WebView/default browser UA at runtime;
- avoid obviously synthetic crawler/desktop identities for the visible login page;
- if the default UA contains an embedded-WebView marker that demonstrably causes Meta login challenge loops, normalize to a Chrome-like mobile UA based on the current WebView version rather than a permanently hardcoded old Chrome number.

Do not silently broaden this into extractor fingerprint spoofing.

### Optional metadata

Architect the WebView login result so login UA can be retained later if evidence shows authenticated requests must replay the login UA. Do not make a large authenticated-request identity rewrite part of V1 unless owner-device evidence requires it.

---

## 12. Security requirements

Mandatory:

- no password interception;
- no JavaScript credential extraction;
- no `addJavascriptInterface` credential bridge;
- no raw cookie headers in logs;
- no raw cookie headers in Toast/Snackbar;
- no cookie values in exceptions;
- no cookie values in diagnostics;
- no cookie values in test fixtures that resemble real sessions;
- no GitHub secrets for user cookies;
- no external telemetry of auth state beyond coarse non-sensitive state if telemetry ever exists;
- keep encrypted `PlatformCredentialStore` as canonical persistence;
- keep manual clear-session behavior;
- login screen should apply `FLAG_SECURE` to reduce screenshot/screen-record leakage while credentials are visible.

The login page itself is the platform's actual HTTPS page. The app must never render its own username/password form.

---

## 13. Architecture integration

Do not create a second authentication subsystem.

Suggested components:

```text
ui/auth/
  PlatformWebLoginScreen.kt
  PlatformWebLoginState.kt

data/download/http/
  PlatformWebLoginConfig.kt
  WebViewCookieCapture.kt

existing:
  PlatformCookieParser
  AuthenticatedPlatformSessionProvider
  PlatformCredentialStore
  validateSession()
```

Suggested coordinator boundary:

```text
WebView
  ↓
WebViewCookieCapture
  ↓
allowlist + required-cookie policy
  ↓
AuthenticatedPlatformSessionProvider.importCapturedSession(...)
  ↓
CONFIGURED
  ↓
validateSession(...)
  ↓
ACTIVE / CONFIGURED / EXPIRED
```

MainViewModel may expose:
- start login destination/event;
- validation result;
- platform session state.

Do not put WebView itself inside the ViewModel.

---

## 14. Existing extractor contract must remain unchanged

WebView Login only creates validated session material.

Extraction behavior remains V3:

```text
anonymous path first
→ anonymous success: stop
→ explicit reference-backed auth-required/restricted condition
   + ACTIVE session
→ authenticated fallback
```

A newly logged-in account must **not** make all Instagram / Threads / X requests automatically auth-first.

Technical parser/network failures must not send account cookies simply because a session exists.

---

## 15. Error/recovery UX

Suggested states:

```text
PREPARING
LOADING_LOGIN
AWAITING_USER
COOKIE_CANDIDATE
VALIDATING
ACTIVE
CHALLENGE
VALIDATION_FAILED
NETWORK_ERROR
CANCELLED
```

Examples:

### Cookie not yet present
Stay in WebView; no error.

### 2FA / security challenge
Stay in WebView and allow user to complete it.

### Redirect loop / embedded-browser block
Show:
`平台拒絕目前的內嵌登入流程。你仍可使用「手動匯入 Cookie」作為備援。`

Do not claim external Chrome can transfer its cookie back into this WebView.

### Validation CONFIGURED
Show:
`已取得 Session，但尚未通過登入驗證。請完成頁面上的登入/安全驗證後重試。`

### Validation EXPIRED
Show:
`平台拒絕此 Session，請重新登入。`

---

## 16. Testing strategy

### Pure JVM/unit tests

Required:
- platform config mapping;
- required-cookie policy;
- allowlist filtering;
- domain isolation;
- Instagram candidate requires `sessionid`;
- X requires **both** `auth_token + ct0`;
- Threads requires `sessionid` candidate;
- no cross-platform cookie import;
- IG→Threads side-probe does not overwrite ACTIVE Threads;
- IG login succeeds even if optional Threads side-probe fails;
- cookie values are never included in diagnostic formatting;
- existing manual import behavior remains green.

### Coordinator tests with fake cookie source

Do not require real Instagram/X/Threads network access in unit tests.

Inject/fake the cookie capture boundary:
- no cookies → stay awaiting;
- partial X cookies → not complete;
- complete cookie candidate → import then validate;
- validator ACTIVE → success;
- validator CONFIGURED/EXPIRED → not success;
- cancellation → scrub callback.

### Android/instrumented tests

Where practical:
- WebView lifecycle;
- cleanup callback ordering;
- `FLAG_SECURE`;
- custom-scheme blocking/fallback;
- cookie capture adapter with synthetic local page/cookies.

Do not embed real platform credentials.

### Owner-device acceptance

Required before calling this feature complete:

Instagram:
1. fresh login;
2. optional 2FA path if account supports it;
3. ACTIVE after current-user validation;
4. app restart still ACTIVE;
5. restricted/login-required Instagram sample can use auth fallback;
6. public Instagram sample remains anonymous-first;
7. clear session removes auth behavior.

Threads:
1. direct WebView login;
2. ACTIVE validation;
3. optional IG→Threads side-probe observed and classified;
4. public sample remains anonymous-first;
5. restricted sample only auth-fallbacks on V3 evidence.

X:
1. login obtains both `auth_token` and `ct0`;
2. ACTIVE validation;
3. sensitive/adult sample that requires login works via authenticated path;
4. ordinary public sample remains anonymous-first.

Security:
- no raw cookies in Logcat;
- no raw cookies in UI;
- cancellation cleans WebView state;
- screenshot protection active on login screen.

---

## 17. CI / development workflow

Current project rule remains:

```text
implementation
→ local ./gradlew test
→ local ./gradlew lintDebug
→ local ./gradlew assembleDebug
→ push feature branch
→ ChatGPT review
→ blockers? fix locally, no GitHub CI
→ NO BLOCKING FINDINGS / READY FOR CI
→ one manual GitHub Actions run
→ owner-device test
```

Workflow remains:
`workflow_dispatch` only.

Artifact retention remains 2 days.

No automatic CI on implementation pushes.

---

## 18. Branch / PR plan

Do not implement inside PR #2.

Recommended order:

### Phase A — finish current baseline
- keep PR #2 head exactly `7c40c0e...` until owner-device baseline test / merge decision;
- Run #42 already passed.

### Phase B — WebView Login implementation
After PR #2 merges:
- sync `main`;
- create:
  `feat/android-webview-login-v1`
- implement only this handoff;
- open a new PR.

If owner explicitly chooses stacked development before PR #2 merge:
- branch from exact `7c40c0e...`;
- clearly mark dependency on PR #2;
- rebase onto post-merge main before final review.

---

## 19. Implementation phases

Keep implementation reviewable.

### Phase 1 — infrastructure + Instagram
- config model;
- WebView screen/coordinator;
- safe cookie capture;
- Instagram login;
- existing provider import + validation;
- cleanup;
- manual import retained.

Review before broadening.

### Phase 2 — Threads
- direct Threads login;
- existing Threads validation;
- optional IG→Threads side-probe;
- no overwrite of ACTIVE Threads.

Review.

### Phase 3 — X
- X login;
- exact `auth_token + ct0` completion;
- validation;
- regression tests.

Review.

Do not implement all three as an unreviewed monolithic change if Phase 1 uncovers WebView/platform constraints.

---

## 20. Acceptance gate

Feature is not complete merely because a WebView opens.

Definition of done:

- all three platform login entry points exist;
- cookies are captured on-device without desktop export;
- only allowlisted session cookies are persisted;
- encrypted store remains canonical;
- session becomes ACTIVE only after existing validation;
- manual import remains functional;
- anonymous-first extraction is unchanged;
- no auth secrets in logs/UI/tests/GitHub;
- local Gradle gates pass;
- review has no blocker;
- manual CI passes;
- owner-device login + download acceptance passes.

---

## 21. Antigravity implementation report format

When implementation is authorized, report:

```text
Base commit:
Feature branch:
Implementation commit(s):

Reference recheck:
- FastMediaSorter:
- ig-image-downloader:
- Otter:
- licensing precautions:

Phase implemented:
- Infrastructure:
- Instagram:
- Threads:
- X:

Security:
- password interception: NO
- Javascript credential bridge: NO
- raw cookie logging: NO
- encrypted store reused: YES
- FLAG_SECURE:
- WebView cleanup:

Cookie completion:
- Instagram:
- Threads:
- X:

IG -> Threads side-probe:

Anonymous-first regression:

Tests added:

Local unit tests:
Local lint:
Local assembleDebug:

GitHub CI triggered: NO
Merged: NO
Release created: NO

Known limitations:
Review gate: READY FOR REVIEW
```
