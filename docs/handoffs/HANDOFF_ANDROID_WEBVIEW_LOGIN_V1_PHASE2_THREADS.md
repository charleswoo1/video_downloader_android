# HANDOFF — Android WebView Login V1 / Phase 2 — Threads Direct Login

Repository: `charleswoo1/video_downloader_android`

Branch: `feat/android-webview-login-v1-threads`

Base branch: `main`

Base commit: `bf455b4e6c3483032c4e0ee40e3daa02cee927e5`

Previous milestone:
- PR #3 `feat(auth): add Instagram WebView login v1` is merged.
- Instagram WebView Login Phase 1 is owner-device verified.
- Android CI Run #45 passed on reviewed head `7bd9978669329cbab3f8e0acdb24428236faf40a`.
- Owner-device verification confirmed Instagram session becomes ACTIVE and multiple Instagram videos download successfully.

Phase 2 goal: add **direct Threads WebView login** by reusing the Phase 1 infrastructure. Do not redesign authentication, do not broaden extractor behavior, and do not implement X in this phase.

---

## 1. Reference recheck

Rechecked before this handoff:

### FastMediaSorter_mob_v2

Repository:
`SerZhyAle/FastMediaSorter_mob_v2`

Observed main head:
`65b0cd792f567c77164bc3d49aaceb8b68a7e5bf`

Current `KnownAuthResources.kt` still declares:

- `https://www.threads.com/login`
- legacy `https://www.threads.net/login`

The current project should treat **threads.com as canonical** for Phase 2. Do not add threads.net probing unless owner-device evidence shows it is required.

License: Apache-2.0.

Behavioral patterns may be independently reimplemented. If source expression is copied/adapted, preserve required Apache attribution.

### ig-image-downloader

Repository:
`daveli07098/ig-image-downloader`

Observed main head:
`f9474bd9d3f6434d9fecca16fc4a63b95efc1164`

Current implementation documents that Meta may sometimes set `threads.com` cookies as a side effect of Instagram login, but it does not establish that this always occurs.

Therefore Phase 2 must implement a **direct Threads login path**. Instagram → Threads opportunistic side-probe remains deferred and must not be required for Threads authentication.

This repository is behavioral/reference evidence only unless licensing is independently clarified. Do not copy source expression.

---

## 2. Scope

Implement only:

1. Threads entry in the existing declarative WebView login catalog.
2. Threads login button / relogin button in Platform Sessions.
3. Reuse the existing full-screen `PlatformWebLoginScreen`.
4. Reuse native Android `CookieManager`.
5. Capture only Threads allowlisted cookies.
6. Import via the existing `AuthenticatedPlatformSessionProvider.importCapturedSession()`.
7. Validate via the existing Threads `validateSession()`.
8. Only `SessionState.ACTIVE` counts as successful login.
9. Generalize any remaining Instagram-specific coordinator/UI strings required for Threads.
10. Add focused Phase 2 regression tests.

Do NOT implement:

- X WebView login.
- Instagram → Threads automatic side-probe.
- account switching / multi-account.
- OAuth token login.
- native Instagram/Threads app cookie access.
- extractor rewrite.
- auth-first Threads extraction.
- broad Threads GraphQL changes.
- automatic CI triggers.
- release/tag/version bump.

---

## 3. Architecture invariant

Keep the Phase 1 architecture:

```text
Platform Sessions
→ startWebLogin(THREADS)
→ PlatformWebLoginScreen
→ Android CookieManager
→ WebViewCookieCapture
→ allowlisted candidate
→ importCapturedSession(THREADS)
→ CONFIGURED
→ validateSession(THREADS)
→ ACTIVE / CONFIGURED / EXPIRED
```

Extraction remains:

```text
anonymous path first
→ anonymous success: stop
→ explicit reference-backed auth-required/restricted evidence
   + ACTIVE Threads session
→ authenticated Threads fallback
```

Do not change this routing.

---

## 4. Threads WebView config

Add a `THREADS` config in `PlatformWebLoginCatalog`.

Required Phase 2 values:

```kotlin
val THREADS = PlatformWebLoginConfig(
    platform = Platform.THREADS,
    loginUrl = "https://www.threads.com/login",
    cookieProbeUrls = listOf("https://www.threads.com/"),
    requiredCookieNames = setOf("sessionid"),
    optionalCookieNames = setOf("csrftoken", "ds_user_id"),
    allowThirdPartyCookies = true,
    intermediatePatterns = listOf(
        "/login",
        "/accounts/login/",
        "/accounts/onetap/",
        "/accounts/password/",
        "/challenge/",
        "/two_factor",
        "/verify/",
        "security_check",
        "checkpoint"
    ),
    postLoginProbe = null
)
```

Then:

```kotlin
configFor(Platform.THREADS) -> THREADS
```

Keep Instagram config unchanged.

Do not add legacy threads.net cookie probing in this phase unless a real-device failure proves it is necessary.

---

## 5. Candidate completion

Threads candidate completion requires both:

1. current page is a valid Threads origin;
2. native CookieManager for `https://www.threads.com/` contains nonblank `sessionid`.

Valid current origins:

- `threads.com`
- `*.threads.com`

The existing shared origin helper may continue allowing threads.net for compatibility, but Phase 2 login/probe is threads.com only.

Do not consider these valid Threads origins:

- `threads.com.evil.example`
- `evilthreads.com`
- `instagram.com`
- `facebook.com`
- arbitrary Meta domains

A Threads login may temporarily navigate through Instagram/Meta authentication pages. Those pages may remain visible and interactive, but they must not themselves trigger a Threads candidate import.

After navigation returns to Threads and `sessionid` is visible in the Threads cookie jar:

```text
candidate
→ allowlist
→ import
→ validate Threads
```

Do not mark success from URL alone.

---

## 6. Cookie policy

Persist only:

Required:
- `sessionid`

Optional:
- `csrftoken`
- `ds_user_id`

Do not persist the full Threads WebView cookie jar.

Do not persist tracking/ad cookies.

Do not merge Instagram cookie headers into Threads storage.

Do not overwrite Instagram credential storage.

The existing `PlatformCookieParser` domain isolation remains authoritative.

Threads captured header import should continue assigning the project's canonical Threads domain (`threads.com`).

---

## 7. Threads validation contract

Do not replace the current Threads validator as part of Phase 2 unless a concrete test failure requires a narrowly scoped fix.

Current ACTIVE proof remains:

```text
Threads page HTTP success
+
DTSG evidence
+
documented page user ID OR credible ds_user_id cookie
→ ACTIVE
```

HTTP 200 alone must not produce ACTIVE.

Candidate cookie presence alone must not produce ACTIVE.

If validation remains CONFIGURED:
- keep WebView available;
- do not show success;
- allow user to finish login/challenge and retry.

If validation becomes EXPIRED:
- show a recoverable login rejection state;
- do not claim success.

Transient network/server errors must not destroy an already ACTIVE session. Preserve the existing state semantics unless tests expose a regression.

Do not weaken the V3 Threads validator merely to make WebView login turn green.

---

## 8. Generalize Phase 1 Instagram-specific UI/coordinator copy

Phase 1 intentionally shipped Instagram first, and some strings remain Instagram-specific.

Phase 2 must remove only the hardcoded text that is now incorrect for Threads.

Examples currently requiring review:

```text
已取得 Session，但尚未完成登入驗證，請完成 Instagram 頁面上的驗證後再試。
Instagram 拒絕此 Session，請重新登入。
```

Use:

```kotlin
config.platform.displayName
```

or equivalent platform-safe copy.

Examples:

```text
已取得 Session，但尚未完成登入驗證，請完成 Threads 頁面上的驗證後再試。
Threads 拒絕此 Session，請重新登入。
```

Prefer even more generic wording where possible:

```text
已取得 Session，但尚未完成登入驗證，請完成頁面上的安全驗證後再試。
此 Session 未通過 Threads 驗證，請重新登入。
```

Do not introduce a second coordinator.

The same `PlatformWebLoginCoordinator` must support Instagram and Threads.

---

## 9. Threads login UX

Platform Sessions Threads card:

### NOT_CONFIGURED

```text
Threads                            未設定

[登入 Threads]       [手動匯入 Cookie]
```

### CONFIGURED / ACTIVE / EXPIRED

Provide the same responsive FlowRow pattern already used by Instagram:

```text
[重新登入] [驗證狀態] [更新 Cookie] [清除]
```

Threads `onWebLogin` must call:

```kotlin
onStartWebLogin(Platform.THREADS)
```

Do not modify X card behavior.

When Threads login is opened:
- close Platform Sessions dialog;
- show full-screen WebView;
- on cancel/back return to Platform Sessions;
- on ACTIVE return to Platform Sessions and show 已連線.

---

## 10. WebView behavior

Reuse Phase 1 behavior unchanged:

- fresh CookieManager scrub before load;
- wait for removal callback;
- `flush()` only after callback;
- JavaScript enabled;
- DOM storage enabled;
- third-party cookies enabled;
- normalized browser UA;
- `FLAG_SECURE`;
- no JavaScript bridge;
- no password interception;
- no credential form owned by this app;
- HTTP/HTTPS allowed;
- arbitrary custom schemes blocked;
- safe `intent://` browser fallback only;
- cookie scrub on exit;
- WebView cache/history/form cleanup;
- WebView destroy.

Do not launch the native Threads or Instagram app from the login WebView. Native app handoff would prevent reliable WebView cookie capture.

---

## 11. Meta / Instagram intermediate authentication

Threads may route the user through Instagram/Meta account authentication.

This is permitted navigation.

Important:

```text
current origin = instagram.com
→ do NOT import Threads candidate
```

Even if a Threads cookie already exists in CookieManager, candidate validation must wait until a valid Threads origin is observed.

Intermediate Instagram-style challenge URLs should remain interactive:

- `/accounts/login/`
- `/accounts/onetap/`
- `/challenge/`
- `/two_factor`
- `/verify/`
- security/checkpoint pages

These URLs are UI state hints only.

They must not be treated as success or terminal failure by URL alone.

---

## 12. Security invariants

Mandatory:

- no password interception;
- no `document.cookie` auth extraction;
- no `addJavascriptInterface`;
- no raw cookies in logs;
- no raw cookies in Toast/Snackbar;
- no cookies in diagnostics;
- no cookies in test fixtures resembling real credentials;
- no external cookie upload;
- encrypted credential store remains canonical;
- Threads cookies remain isolated from Instagram and X storage;
- manual Cookie import remains available;
- `FLAG_SECURE` remains active while login WebView is visible.

Do not persist Threads username or other profile data merely for UI display.

---

## 13. Required tests

### Config tests

Add/extend tests proving:

1. `configFor(THREADS)` exists.
2. login URL is `https://www.threads.com/login`.
3. probe URL is canonical `https://www.threads.com/`.
4. required cookies exactly include `sessionid`.
5. optional allowlist contains `csrftoken` and `ds_user_id`.
6. X remains unsupported by WebView config in Phase 2.

### Origin tests

Existing helper must prove:

```text
https://www.threads.com/
https://threads.com/@user/post/...
→ valid Threads origin

https://threads.com.evil.example/
https://evilthreads.com/
https://www.instagram.com/
→ invalid Threads origin
```

Do not regress Instagram origin tests.

### Cookie candidate tests

For Threads config:

- no `sessionid` → no candidate;
- `sessionid` only → candidate;
- `sessionid + csrftoken + ds_user_id` → candidate containing only allowlisted values;
- unrelated cookie omitted;
- duplicate cookie behavior deterministic;
- candidate string/debug representation must not leak values.

### Provider tests

- captured Threads session missing `sessionid` fails;
- valid captured Threads session imports as CONFIGURED;
- Instagram captured import remains green;
- manual Threads cookie import remains green.

### Coordinator tests

Reuse the same coordinator; add at least:

1. Threads no-cookie → AwaitingUser.
2. Threads candidate + ACTIVE validator → Active + success callback.
3. Threads candidate + CONFIGURED → recoverable state, no success callback.
4. Threads candidate + EXPIRED → error/retry, no success callback.
5. current Instagram origin + existing Threads sessionid → no Threads validation trigger.
6. current Threads origin + sessionid → validation may trigger.
7. cancel/exit → completion-safe cleanup.

### UI/string regression

Ensure coordinator messages do not hardcode `Instagram` when platform is Threads.

### Full regression

Run the entire unit suite.

Do not replace existing Instagram tests.

---

## 14. Required local gate

Run:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

All must PASS.

Then commit and push only to:

`feat/android-webview-login-v1-threads`

STOP for ChatGPT review.

Do NOT trigger GitHub Actions yet.
Do NOT open PR yet.
Do NOT merge.
Do NOT tag.
Do NOT release.

---

## 15. Owner-device gate after review + manual CI

After ChatGPT review says READY FOR MANUAL CI and the manually triggered CI passes:

1. install the CI APK;
2. verify existing Instagram session/login still works;
3. open Platform Sessions;
4. tap `登入 Threads`;
5. complete Threads/Instagram/Meta login entirely inside the WebView;
6. complete any challenge/2FA;
7. verify Threads card becomes `已連線 / ACTIVE`;
8. close and reopen app and verify Threads ACTIVE persists;
9. test multiple Threads videos, including:
   - at least one previously working public sample;
   - at least two previously failing/mixed samples if available;
10. verify normal public Threads extraction remains anonymous-first;
11. verify authenticated fallback only occurs after explicit auth/restricted evidence;
12. clear Threads session and confirm authenticated Threads behavior disappears;
13. confirm clearing Threads does not clear Instagram;
14. confirm existing Instagram downloads still work.

If direct Threads login never creates a usable `threads.com` `sessionid`, STOP and report real-device evidence. Do not automatically broaden to Instagram side-probe, cross-domain cookie synthesis, or extractor rewrites.

---

## 16. Phase completion criteria

Phase 2 is COMPLETE only when all are true:

- code review: no blocking findings;
- manual GitHub CI: PASS;
- Threads WebView direct login: owner-device PASS;
- Threads session: ACTIVE;
- persistence after app restart: PASS;
- multiple Threads downloads: PASS;
- Instagram WebView regression: PASS;
- anonymous-first routing preserved;
- cookie isolation preserved.

Only then open PR and merge.

---

## 17. Agent report

Return exactly enough information for review:

```text
PHASE 2 — WEBVIEW LOGIN / THREADS

Base commit:
Branch:
Implementation head:

Reference recheck:
- FastMediaSorter head:
- ig-image-downloader head:
- direct Threads canonical login URL:

Files changed:

Threads config:
- login URL:
- probe URL:
- required cookies:
- optional cookies:

Coordinator reuse:
- new coordinator created? NO
- Instagram-specific strings generalized:
- candidate origin behavior:

Cookie capture:
- native CookieManager:
- allowlist:
- raw secret logging: NO

Provider:
- captured Threads import:
- existing Threads validator reused:
- ACTIVE proof changed?:

Extractor:
- NativeThreadsEngine changed? NO
- anonymous-first changed? NO

Tests added:

Local unit tests:
Local lint:
Local assembleDebug:

GitHub CI triggered: NO
PR opened: NO
Merged: NO
Release: NO

Known limitations:
Ready for ChatGPT review: YES
```
