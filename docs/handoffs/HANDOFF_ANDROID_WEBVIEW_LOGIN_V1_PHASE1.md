# HANDOFF — Android WebView Login V1 / Phase 1

Repository: `charleswoo1/video_downloader_android`

Feature branch: `feat/android-webview-login-v1`

Base main commit: `74b801dea2dff2fd360aefe596cc37ba3e4eb4db`

Governing design:
`docs/handoffs/HANDOFF_ANDROID_WEBVIEW_LOGIN_V1.md`

Status:
**Phase 1 implementation authorized after design gate.**

Scope:
**shared WebView-login infrastructure + Instagram only.**

Do not implement Threads direct login or X login in Phase 1.
Do not broadly rewrite Instagram extraction.
Do not trigger GitHub Actions.

---

## 1. Baseline

PR #2 was squash-merged into main:

`74b801dea2dff2fd360aefe596cc37ba3e4eb4db`

The reviewed code head before merge was:

`7c40c0ec7898c8af21cc24b6120cd501c47a54e5`

Manual Android CI Run #42 passed:
- unit tests;
- Android lint;
- assembleDebug;
- artifact upload.

Owner-device result is still mixed: some IG / Threads / X samples work and some fail.

Interpretation:
- preserve V3 as the baseline;
- Phase 1 is meant to make a valid Instagram authenticated session obtainable entirely on-device;
- do not respond to remaining sample failures by widening anonymous parser heuristics during this phase.

---

## 2. Reference-first hard gate

Before editing production code, inspect CURRENT versions of:

### Primary
`SerZhyAle/FastMediaSorter_mob_v2`
- `KnownAuthResources.kt`
- `WebViewAuthDialogFragment.kt`

Use for behavioral patterns:
- fresh login cookie cleanup;
- visible WebView;
- Android CookieManager;
- custom-scheme handling;
- WebView cleanup.

Apache-2.0: if source expression is copied/adapted, preserve required attribution. Prefer independent implementation.

### Behavioral corroboration only
`daveli07098/ig-image-downloader`
- `lib/screens/login_screen.dart`
- native Android CookieManager MethodChannel

Use for:
- Instagram login URL;
- HttpOnly session capture;
- challenge / redirect-loop evidence;
- no native-app handoff.

No root license was observed during design review: **do not copy source**.

### Behavioral corroboration only
`777abhishek/Otter`
- `WebViewLoginScreen.kt`

GPL-3.0: **do not copy GPL source**.

Record current reference SHAs in the implementation report.

---

## 3. Phase 1 product behavior

Existing Platform Sessions dialog currently offers:

```text
Instagram
[匯入 Cookie]
```

Change Instagram card to make on-device login the primary path:

```text
Instagram                         未設定

[登入 Instagram]
[手動匯入 Cookie]
```

For CONFIGURED / ACTIVE / EXPIRED:

```text
[重新登入]
[驗證狀態]
[更新 Cookie]
[清除]
```

Do not remove manual Cookie import.

Threads and X cards remain functionally unchanged in Phase 1.

---

## 4. UI architecture

The app currently has a single `MainActivity` and no navigation framework.

Do **not** add a navigation library solely for this feature.

Preferred Phase 1 structure:

```text
MainActivity
  └─ MainScreen
       ├─ normal downloader UI
       └─ PlatformWebLoginScreen (when loginPlatform != null)
```

A simple top-level Compose state / screen-mode is sufficient.

The WebView itself must remain a UI object:
- do not store WebView in MainViewModel;
- do not retain Activity/WebView references in repositories;
- do not leak Context.

Suggested files:

```text
ui/auth/PlatformWebLoginScreen.kt
ui/auth/PlatformWebLoginState.kt
data/download/http/PlatformWebLoginConfig.kt
data/download/http/WebViewCookieCapture.kt
```

Exact names may differ if a cleaner fit exists, but do not create a second authentication subsystem.

---

## 5. Instagram configuration

Phase 1 config:

```text
platform:
INSTAGRAM

login URL:
https://www.instagram.com/accounts/login/

cookie probe:
https://www.instagram.com/

required cookie:
sessionid

optional persisted allowlist:
csrftoken
ds_user_id
```

The login completion signal is a **candidate**, not final authentication:

```text
Instagram-domain page
+
CookieManager probe contains sessionid
→ candidate
→ import into existing provider as CONFIGURED
→ existing validateSession(INSTAGRAM)
→ only ACTIVE means login success
```

Do not mark ACTIVE directly from WebView cookie presence.

---

## 6. Cookie capture

Use:

`android.webkit.CookieManager.getInstance().getCookie("https://www.instagram.com/")`

Do not use:
- JavaScript `document.cookie`;
- `addJavascriptInterface`;
- injected credential-reading JavaScript;
- accessibility scraping.

HttpOnly session cookie capture must rely on Android CookieManager.

### Filtering

From the raw cookie header, retain only:

```text
sessionid
csrftoken
ds_user_id
```

Do not store unrelated Instagram tracking cookies.

Do not log:
- raw header;
- cookie values;
- value lengths that could become identifying diagnostics.

Logging may include only coarse booleans/counts, e.g.:
`instagram_cookie_candidate=true required_cookie_present=true`.

---

## 7. Provider integration

Reuse:
- `PlatformCookieParser`;
- `AuthenticatedPlatformSessionProvider`;
- `EncryptedPlatformCredentialStore`;
- existing Instagram `validateSession()`.

Add one typed capture-import path rather than routing WebView cookies through the manual text dialog.

Preferred:

```kotlin
fun importCapturedSession(
    platform: Platform,
    rawCookieHeader: String
): Result<PlatformSessionInfo>
```

It must:
1. reuse platform parsing/domain isolation;
2. enforce the same mandatory-cookie rule as manual import;
3. save through the same canonical credential store;
4. leave state CONFIGURED until validation succeeds.

Avoid duplicating mandatory-cookie logic in multiple places. Refactor into a shared internal helper if needed.

Manual import behavior must remain unchanged.

---

## 8. Validation behavior

After captured Instagram cookies are stored:

call existing:
`validateSession(Platform.INSTAGRAM, ...)`

Existing ACTIVE proof remains authoritative:
- successful current-user response;
- JSON `status == "ok"`;
- `user` object exists;
- positive numeric `pk`.

Outcomes:

### ACTIVE
- show success;
- close WebView;
- return to sessions UI.

### CONFIGURED
- do not claim login success;
- keep/reopen WebView state with:
  `已取得 Session，但尚未完成登入驗證，請完成 Instagram 頁面上的驗證後再試。`

### EXPIRED / explicit rejection
- show:
  `Instagram 拒絕此 Session，請重新登入。`

### transient network validation failure
- do not discard candidate session immediately;
- preserve CONFIGURED state and provide retry.

---

## 9. WebView lifecycle

### Fresh login

Before loading Instagram:

1. create WebView;
2. configure CookieManager;
3. call `removeAllCookies(callback)`;
4. `flush()`;
5. clear WebView cache/history/form data;
6. only load the login URL **after the removal callback**.

Do not race cookie deletion and first navigation.

### While open

Required:
- JavaScript enabled;
- DOM storage enabled;
- cookies enabled;
- third-party cookies enabled if current Instagram flow requires them;
- visible user-driven page.

### Exit

On:
- successful ACTIVE validation;
- user cancel/back;
- terminal failure;

perform:
- `removeAllCookies`;
- `flush`;
- `clearCache(true)`;
- `clearHistory()`;
- `clearFormData()`;
- `stopLoading()`;
- remove/destroy WebView safely.

Cleanup must not clear the encrypted credential store.

---

## 10. FLAG_SECURE

While the login screen is visible, protect the Activity window with:

`WindowManager.LayoutParams.FLAG_SECURE`

Requirements:
- set on entering login screen;
- remove on leaving it;
- use `DisposableEffect` or an equivalent lifecycle-safe mechanism;
- do not leave the entire app permanently FLAG_SECURE after login screen closes.

Add an Android/instrumentation-level test where practical, or isolate the window-policy helper enough for deterministic verification.

---

## 11. Custom schemes and native-app handoff

Instagram may attempt:
- `intent://`;
- native Instagram schemes;
- Play Store schemes.

Phase 1 goal is to retain login inside the WebView so CookieManager can capture the web session.

Rules:
- allow normal `http` / `https`;
- allow controlled internal `about:` if needed;
- for `intent://`, parse `browser_fallback_url`;
- load fallback only if it is HTTP(S);
- block arbitrary external schemes;
- do not automatically launch installed Instagram app;
- do not launch arbitrary external intents.

Add tests for URL/scheme decision logic as pure functions where possible.

---

## 12. User-Agent

Do not copy a hardcoded UA from another repo.

Start with Android WebView's runtime/default UA.

If current reference/device evidence shows the `wv` marker causes Instagram challenge loops:
- add a small, testable normalizer;
- derive from current runtime UA/WebView version;
- remove only the embedded-WebView marker/normalize to a plausible current mobile Chrome identity;
- do not hardcode a stale Chrome 120/134 forever.

Do not make extractor HTTP identity changes in Phase 1.

---

## 13. Challenge / 2FA behavior

Do not write a custom challenge solver.

The visible real Instagram page should be allowed to handle:
- 2FA;
- `/challenge/`;
- password reset;
- one-tap/save-login interstitial;
- verification/security pages.

Cookie candidate checks may run on `onPageFinished` and/or another bounded UI lifecycle signal, but must not:
- spin in a tight timer;
- hammer CookieManager/network;
- auto-close merely because the URL is no longer `/accounts/login/`.

Final authority remains:
`sessionid candidate → existing validateSession → ACTIVE`.

For redirect-loop WebResourceError:
- stop treating repeated redirect as successful login;
- show a recoverable message and manual-cookie fallback;
- no automatic external-browser claim that cookies can be returned.

---

## 14. Instagram → Threads side-probe

The overall V1 design includes this convenience, but **do not enable it in Phase 1 production behavior yet**.

Reason:
- first prove the shared WebView + Instagram capture lifecycle;
- side-probe changes a second platform's credential state and should be reviewed separately in Phase 2.

Phase 1 may create a clean extension point/interface for post-login probes, but it must be a no-op for now.

Do not alter Threads credential storage in Phase 1.

---

## 15. Extractor invariants

Do not modify `NativeInstagramEngine` unless a compile-only interface change is truly unavoidable.

Specifically preserve:

```text
anonymous first
→ success: stop
→ explicit auth-required / gated / restricted evidence
   + ACTIVE Instagram session
→ authenticated fallback
```

Do not make WebView login imply:
- auth-first;
- cookies on anonymous GraphQL;
- authenticated fallback on technical/network/parser errors.

Existing V3 tests must remain green.

---

## 16. Security acceptance

Must be true:

```text
password interception: NO
custom username/password UI: NO
document.cookie credential capture: NO
addJavascriptInterface credential bridge: NO
raw cookies in Logcat: NO
raw cookies in Toast/Snackbar: NO
raw cookies in diagnostics: NO
raw cookies in GitHub/tests: NO
external upload: NO
encrypted store reused: YES
FLAG_SECURE on login: YES
manual clear-session retained: YES
```

Search the final diff for:
- `sessionid=`
- `csrftoken=`
- `Cookie:`
- `getCookie(`
- log statements near cookie handling

and verify no secret leakage.

Synthetic test values must be obvious placeholders.

---

## 17. Required tests

### Config / parser tests
- Instagram config resolves correct login URL/probe URL;
- required name = `sessionid`;
- allowlist excludes unrelated cookie names;
- domain remains Instagram-only.

### Capture tests
- raw cookie header with no `sessionid` → not complete;
- `sessionid` only → candidate accepted;
- `sessionid + csrftoken + ds_user_id` → only allowlisted values passed to provider;
- unrelated cookie values not persisted;
- duplicate cookie names have deterministic behavior.

### Provider tests
- captured import missing `sessionid` fails;
- captured import valid candidate becomes CONFIGURED;
- manual import regression remains green;
- final ACTIVE only comes from existing validator.

### Coordinator/state tests
- fresh login waits for cookie-clear callback before load;
- candidate + ACTIVE validation → success/close;
- candidate + CONFIGURED → remains recoverable, not success;
- candidate + EXPIRED → login rejected state;
- cancel → cleanup;
- no cookie → awaiting user.

### Scheme tests
- https allowed;
- http allowed;
- intent with safe HTTPS browser_fallback_url → fallback accepted;
- arbitrary app scheme blocked;
- malformed intent blocked.

### Regression
Run entire existing unit suite. Do not replace old tests with only new focused tests.

---

## 18. Local gates

After Phase 1 implementation:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

All must pass locally.

Then push to:
`feat/android-webview-login-v1`

Stop for ChatGPT review.

Do **not** trigger GitHub Actions.

Do not merge.
Do not tag.
Do not release.

---

## 19. Owner-device test after review/CI

After ChatGPT says `NO BLOCKING FINDINGS / READY FOR CI` and manual CI passes:

Instagram test:

1. open Platform Sessions;
2. tap `登入 Instagram`;
3. login entirely on phone;
4. complete any 2FA/challenge;
5. return to session card showing ACTIVE;
6. close/reopen app, verify session persists;
7. test at least:
   - one previously working public IG sample;
   - one previously failing IG sample;
8. verify public sample still uses anonymous-first trace;
9. verify gated sample uses auth fallback only when reference-backed auth evidence occurs;
10. clear Instagram session and verify authenticated behavior disappears.

Do not call the entire IG platform fixed based solely on successful login.

---

## 20. Antigravity task report

Return:

```text
PHASE 1 — WEBVIEW LOGIN / INSTAGRAM

Base commit:
Branch:
Implementation head:

Reference recheck:
- FastMediaSorter SHA:
- ig-image-downloader SHA:
- Otter SHA:
- licensing precautions:

Files changed:

WebView lifecycle:
- fresh-cookie cleanup:
- load-after-callback:
- exit cleanup:
- destroy:

Cookie capture:
- native CookieManager:
- allowlist:
- required sessionid:
- secret logging check:

Provider integration:
- captured import:
- CONFIGURED behavior:
- existing validator reuse:

Instagram validation:
- ACTIVE proof:
- CONFIGURED behavior:
- EXPIRED behavior:

Security:
- FLAG_SECURE:
- password interception: NO
- JS credential extraction: NO
- addJavascriptInterface: NO

Extractor regression:
- NativeInstagramEngine behavior changed?:
- anonymous-first preserved:

Tests added:

Local unit tests:
Local lint:
Local assembleDebug:

GitHub CI triggered: NO
PR opened:
Merged: NO
Release created: NO

Known limitations:
Review gate: READY FOR REVIEW
```
