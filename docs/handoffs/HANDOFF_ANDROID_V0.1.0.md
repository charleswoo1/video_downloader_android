# HANDOFF — Android v0.1.0 Initial Implementation

Repository: `charleswoo1/video_downloader_android`

Target milestone: `v0.1.0`

Status: **implementation contract / not a release authorization**

## 0. Mission

Build the first installable Android version of **Social Video Downloader** as a native Android application.

The core user experience is:

```text
Social app
  ↓ Share
Social Video Downloader
  ↓
Extract URL from shared text
  ↓
Detect platform
  ↓
Analyze media with yt-dlp
  ↓
Show metadata + download choices
  ↓
Download / merge media
  ↓
Save to Android Downloads
  ↓
Show completion notification
```

The app must also support pasting a URL directly on the main screen.

This repository is Android-only. Do not modify the Windows repository unless a later task explicitly requests cross-repo work.

---

## 1. Mandatory workflow

Before implementation:

1. Read `AGENTS.md`, `README.md`, `CONTRIBUTING.md`, and this contract.
2. Read current `main` and any open Issue / PR relevant to this milestone.
3. Create one implementation Issue for Android `v0.1.0` if one does not already exist.
4. Create a feature branch from the latest `main`.
5. Implement the milestone on that branch.
6. Run all required tests and build a debug APK.
7. Open a Pull Request to `main`.
8. Do **not** merge the PR unless explicitly instructed by the repository owner.
9. Do **not** create a tag or GitHub Release.

The PR description must include:

- implemented scope;
- known limitations;
- test results;
- tested Android version / device or emulator information;
- debug APK artifact information;
- yt-dlp / youtubedl-android / FFmpeg versions actually used.

---

## 2. Product scope for v0.1.0

### In scope

- Native Android application.
- Kotlin.
- Jetpack Compose UI.
- Receive shared `text/plain` content from Android Sharesheet.
- Extract HTTP / HTTPS URL(s) from arbitrary shared text.
- Select the first supported URL deterministically when more than one URL is present.
- Detect these platforms:
  - YouTube / `youtu.be`
  - Facebook / `fb.watch`
  - Instagram
  - Threads
  - X / Twitter
  - TikTok
  - generic yt-dlp-supported URL
- Analyze a URL using yt-dlp and display at minimum:
  - title;
  - platform / extractor;
  - duration when available;
  - thumbnail when available;
  - basic format / quality choices when available.
- Download video.
- Download audio-only.
- FFmpeg merge/post-processing when required.
- Display active download progress.
- Allow cancellation of an active download.
- Persist completed file into Android shared storage.
- Display completion / failure notification.
- Main screen supports paste / manual URL input.
- GitHub Actions CI builds and uploads a debug APK Artifact.

### Explicitly out of scope for v0.1.0

Do not expand scope unless the owner explicitly asks:

- DRM bypass.
- Private / paid-content bypass.
- Browser cookie extraction.
- Account login UI.
- Cookie import UI.
- Playlist batch downloading.
- Download queue with multiple concurrent jobs.
- Automatic yt-dlp runtime self-update.
- Android TV / Wear / Auto.
- Google Play publication.
- Production signing.
- Automatic GitHub Release publishing.
- Background clipboard monitoring.
- Accessibility-service based URL capture.
- Automatic interception of content from other apps without an explicit Android Share action.

---

## 3. Android baseline

Use the following baseline unless an incompatibility is discovered and documented in the PR:

- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`
- Kotlin
- Jetpack Compose
- Gradle Kotlin DSL
- Java / JDK 17 toolchain unless the selected stable Android Gradle Plugin requires a newer supported baseline.
- Single application module for v0.1.0 unless a clear testability/build reason justifies a second module.

Use current stable Android / Kotlin / Compose dependencies that are compatible with API 36 at implementation time. Pin exact versions in the repository; do not use dynamic `+` dependency versions.

Prefer a Gradle Version Catalog (`gradle/libs.versions.toml`) for dependency versions.

Suggested application ID:

```text
com.charleswoo1.videodownloader
```

App display name:

```text
Social Video Downloader
```

Initial version values:

```text
versionName = "0.1.0"
versionCode = 1
```

Do not create a `v0.1.0` Git tag in this task.

---

## 4. yt-dlp / FFmpeg runtime strategy

Use `youtubedl-android` behind an application-owned abstraction rather than calling it directly from Composables or screens.

Initial candidate dependency:

```text
io.github.junkfood02.youtubedl-android:library:0.18.1
io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1
```

`aria2c` is **not required** for v0.1.0. Do not add it unless there is a demonstrated need.

Important constraints:

1. `0.18.1` is an initial implementation candidate, not an architectural dependency.
2. Wrap the library with an interface such as `MediaExtractor` / `MediaDownloadEngine` so the implementation can be changed later.
3. Do not expose `YoutubeDLRequest`, `VideoInfo`, or other third-party classes to the UI layer.
4. Do not implement automatic yt-dlp binary/runtime updates in v0.1.0.
5. Initialize yt-dlp and FFmpeg outside Composables, preferably from application/runtime initialization code with explicit error handling.
6. If native ABI configuration is required, document it and verify at least `arm64-v8a`; supporting additional ABIs is desirable for debug builds but must not break CI.
7. Verify behavior on a modern arm64 device/emulator. Pay special attention to 16 KB page-size compatibility.
8. If `0.18.1` cannot pass required acceptance tests, do not hide the failure. Document the failing platform/device and propose the smallest justified dependency/runtime adjustment in the PR.

### Licensing checkpoint

`youtubedl-android` is published under GPL-3.0. Before any public binary Release, review and satisfy all applicable third-party licensing obligations, including the chosen FFmpeg build and its license configuration.

For this milestone:

- add `THIRD_PARTY_NOTICES.md` describing major runtime dependencies and their upstream links/licenses;
- do not claim a project-wide license that has not been explicitly selected by the owner;
- treat public production Release as blocked until repository licensing is explicitly decided.

---

## 5. Required architecture

Keep v0.1.0 simple but layered.

Recommended structure:

```text
app/src/main/java/com/charleswoo1/videodownloader/
├── App.kt
├── MainActivity.kt
├── data/
│   ├── download/
│   │   ├── DownloadEngine.kt
│   │   ├── YtDlpDownloadEngine.kt
│   │   └── DownloadRepository.kt
│   └── storage/
│       └── DownloadStorage.kt
├── domain/
│   ├── model/
│   │   ├── MediaInfo.kt
│   │   ├── MediaFormat.kt
│   │   ├── DownloadRequest.kt
│   │   └── DownloadState.kt
│   └── url/
│       ├── SharedTextUrlExtractor.kt
│       └── PlatformDetector.kt
├── service/
│   └── DownloadService.kt
└── ui/
    ├── MainViewModel.kt
    ├── navigation/
    ├── screen/
    ├── component/
    └── theme/
```

Exact filenames may change if implementation quality improves, but preserve these boundaries:

```text
Compose UI
   ↓
ViewModel / UI state
   ↓
Repository / use-case boundary
   ↓
DownloadEngine abstraction
   ↓
youtubedl-android + FFmpeg
```

Do not put yt-dlp command construction directly in Composables.

---

## 6. Share Intent behavior

Register the application as a share target for:

```text
ACTION_SEND
text/plain
```

Expected behavior:

1. User taps Share in another app.
2. User selects Social Video Downloader.
3. App receives `Intent.EXTRA_TEXT`.
4. Parse arbitrary text rather than assuming the payload is a bare URL.
5. Extract valid HTTP / HTTPS URLs.
6. Normalize obvious trailing punctuation safely.
7. Choose the first supported URL.
8. Open/focus the app and immediately populate the analysis flow.
9. If no URL is found, show a clear user-facing message and preserve the original shared text only in memory as needed for the screen; do not log sensitive content unnecessarily.

The Activity must also correctly handle a new share while already alive (for example through `onNewIntent` / equivalent state handling).

Do not rely on clipboard access for the share workflow.

---

## 7. URL extraction and platform detection

Implement these as pure, unit-testable Kotlin components.

### URL extraction requirements

Must handle at least:

```text
https://example.com/video
Check this: https://example.com/video
看看這個 https://example.com/video 很有趣
https://example.com/a https://example.com/b
(https://example.com/video)
https://example.com/video,
```

Must reject obvious non-HTTP(S) strings.

### Platform detection

Map URLs to an internal enum / sealed model such as:

```text
YOUTUBE
FACEBOOK
INSTAGRAM
THREADS
X
TIKTOK
GENERIC
```

Platform detection is a UI hint and routing aid only. yt-dlp remains the final authority for actual extractor support.

---

## 8. Analyze flow

The main screen must have a URL input field and an Analyze action.

For either pasted URL or share-intent URL:

```text
Idle
 ↓
Validating URL
 ↓
Analyzing
 ↓
Success(MediaInfo)
```

or

```text
Analyzing
 ↓
Error(user-readable message)
```

`MediaInfo` should be an app-owned model and include at least:

```text
sourceUrl
title
platform
extractor
thumbnailUrl?
durationSeconds?
formats
```

Do not display raw Python stack traces, command lines, cookies, headers, or tokens in normal UI errors.

A developer-oriented error detail may be logged in debug builds, but sanitize URLs where practical and never log authentication material.

---

## 9. Download choices

v0.1.0 UI must support at least:

### Video

- Best available / automatic.
- When format metadata makes it practical, expose a small set of quality choices such as:
  - 1080p
  - 720p
  - 480p
  - 360p
- Do not present a quality option if the analyzed media cannot satisfy it.
- Prefer MP4-compatible output where practical.
- Use FFmpeg merge when separate video/audio streams are selected.

### Audio

- Audio-only mode.
- Produce a broadly playable audio result; exact codec/container should be chosen based on the runtime's reliable FFmpeg support and documented in the PR.

Keep yt-dlp selector construction inside the download engine layer.

---

## 10. Download execution and lifecycle

Downloads are user-initiated and may continue after the UI leaves the foreground.

For v0.1.0, implement a foreground download service or another Android-supported user-initiated transfer mechanism that provides equivalent lifecycle reliability and notification visibility.

If using a foreground service:

- declare the appropriate foreground service type for data transfer;
- declare the required foreground service permissions for the selected target SDK;
- show an ongoing notification immediately as required by Android;
- stop the service promptly after completion/cancellation/failure;
- implement timeout-safe behavior on modern Android versions;
- do not assume an unlimited background runtime.

State model should include at minimum:

```text
Idle
Preparing
Downloading(progress?, eta?)
PostProcessing
Completed(uri/path)
Cancelled
Failed(message)
```

Cancellation must terminate the corresponding yt-dlp process through the runtime API when possible.

Only one active download is required for v0.1.0.

---

## 11. Storage behavior

Primary v0.1.0 destination:

```text
Downloads/SocialVideoDownloader/
```

Use modern Android shared-storage APIs.

For Android 10+ prefer MediaStore / scoped-storage-compatible behavior rather than legacy unrestricted filesystem assumptions.

Requirements:

- completed downloads are visible to the user outside the app;
- incomplete/temporary files should not appear as successfully completed media;
- sanitize invalid filename characters;
- avoid accidental overwrite; apply deterministic conflict naming such as `(1)`, `(2)` or another documented scheme;
- return the final `content://` URI or equivalent app-owned result to the UI when available;
- no broad storage permission should be requested unless technically necessary and justified.

Because `minSdk = 26`, isolate any pre-API-29 storage fallback. Do not let legacy storage handling contaminate the API-29+ path.

---

## 12. Notifications

Create a dedicated notification channel.

Required states:

- downloading;
- post-processing when applicable;
- completed;
- failed;
- cancelled if useful.

The active notification should show progress when the runtime reports meaningful progress.

Completion notification should open the application and, when feasible, provide access to the downloaded file through a safe `content://` URI.

Android 13+ notification permission behavior must be handled correctly. A denied notification permission must not crash the application; document any resulting background-download limitations.

---

## 13. UI requirements

Keep the first release functional and restrained.

### Main screen

Must include:

- app title;
- URL text field;
- Analyze button;
- loading/analyzing state;
- media result card;
- thumbnail if available;
- title;
- platform;
- duration if available;
- download mode selector;
- quality selector when applicable;
- Download button;
- active progress UI;
- Cancel action while downloading;
- clear success/failure message.

### Share entry

When opened from Android Sharesheet, skip unnecessary manual steps:

```text
Receive share
→ extract URL
→ populate UI
→ begin analysis automatically
```

Do **not** automatically start the final download in v0.1.0. The user must explicitly confirm Download after analysis.

### Language

Use Traditional Chinese (`zh-TW`) as the initial UI language.

Keep user-visible strings in Android string resources rather than hardcoding them in Composables.

---

## 14. State and configuration

Use a lifecycle-aware ViewModel.

Do not introduce a database unless necessary.

For v0.1.0 it is acceptable for current analysis/download screen state to be in memory, provided rotation/recomposition does not cause duplicate downloads or duplicate yt-dlp execution.

If preferences are needed, use Android-appropriate preference storage and keep the scope minimal.

No cookie/login settings are needed in this milestone.

---

## 15. Error handling

Create user-readable categories rather than surfacing raw exceptions.

At minimum cover:

- invalid / missing URL;
- unsupported URL;
- network unavailable / connection failure;
- yt-dlp initialization failure;
- metadata extraction failure;
- FFmpeg initialization / post-processing failure;
- storage write failure;
- download cancellation;
- platform-side restriction / login required when detectable.

Messages should make clear when the failure is caused by a source site's changing behavior rather than implying that every URL is guaranteed to work.

---

## 16. Testing requirements

### Unit tests — mandatory

At minimum:

1. `SharedTextUrlExtractor`
   - bare URL;
   - URL embedded in English text;
   - URL embedded in Chinese text;
   - multiple URLs;
   - punctuation around URL;
   - no URL;
   - non-http scheme rejection.

2. `PlatformDetector`
   - YouTube;
   - youtu.be;
   - Facebook;
   - fb.watch;
   - Instagram;
   - Threads;
   - X;
   - twitter.com;
   - TikTok;
   - generic URL.

3. Download request / format selection mapping.

4. Download state mapping / cancellation behavior that can be tested without performing a real network download.

### Instrumented / integration smoke tests

Where practical:

- `ACTION_SEND` `text/plain` intent opens the correct state;
- manual URL input is accepted;
- notification channel creation does not crash;
- storage integration creates a writable target on supported emulator/device.

### Real runtime verification

Before declaring the PR ready, manually test at least:

- one public YouTube URL;
- one additional public supported social URL if currently available;
- video download;
- audio-only download;
- cancel during download;
- share from another Android app or an equivalent test intent;
- successful file visibility in Downloads.

Do not place unstable third-party live URLs into unit tests that would make CI nondeterministic.

If Instagram/TikTok/Facebook fails because of an upstream runtime/extractor issue, record the exact result in the PR instead of weakening tests or faking support.

---

## 17. GitHub Actions CI

Add a CI workflow for pull requests and pushes to `main`.

Use GitHub-hosted standard runners.

CI should perform at minimum:

```text
checkout
setup JDK
Gradle dependency/cache setup
./gradlew test
./gradlew lint
./gradlew assembleDebug
upload debug APK artifact
```

Pin third-party GitHub Actions to immutable commit SHAs where practical, following the security posture used by the Windows repository.

Artifact naming convention:

```text
SocialVideoDownloader-Android-CI-v0.1.0-run-<run_number>
```

APK inside the artifact should have a clear deterministic name, for example:

```text
SocialVideoDownloader-Android-v0.1.0-debug.apk
```

Artifact retention target:

```text
7 days
```

CI must **not**:

- create tags;
- create GitHub Releases;
- sign with a production keystore;
- require repository secrets for normal debug builds.

---

## 18. Repository files expected from the implementation

Expected baseline after the PR, adjusted only when justified:

```text
.github/
  workflows/
    ci.yml
app/
  build.gradle.kts
  src/
    main/
      AndroidManifest.xml
      java/...
      res/...
    test/...
    androidTest/...
gradle/
  libs.versions.toml
  wrapper/...
build.gradle.kts
settings.gradle.kts
gradle.properties
gradlew
gradlew.bat
README.md
THIRD_PARTY_NOTICES.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
```

Update README to replace "initialization only" language with real developer build instructions once the project builds successfully.

---

## 19. README updates required in this PR

The implementation PR must update README with:

- current Android requirements;
- how to clone/build the debug APK;
- how to install the debug APK for testing;
- how to use the Android share flow;
- supported-platform caveats;
- storage location;
- CI Artifact location/instructions;
- statement that v0.1.0 is pre-release/development until the owner explicitly publishes a Release;
- no claim that every yt-dlp-supported site is guaranteed to work.

Do not add a permanent public Release download link until an actual Release exists.

---

## 20. Security / privacy requirements

Do not request permissions unrelated to the milestone.

Do not collect analytics or telemetry in v0.1.0.

Do not add ad SDKs.

Do not add remote configuration.

Do not transmit URLs anywhere except to the source services/runtime required to analyze/download them.

Do not commit:

- cookies;
- auth tokens;
- API keys;
- keystores;
- signing passwords;
- `local.properties`;
- device identifiers.

Do not use WebView-based credential capture.

---

## 21. Definition of done

The implementation PR is ready for owner review only when all of the following are true:

- [ ] Android project opens/builds with documented tooling.
- [ ] `compileSdk 36` and `targetSdk 36` are configured, or a documented blocker explains any temporary deviation.
- [ ] `minSdk 26` is configured.
- [ ] App launches without crash.
- [ ] Share target appears for `text/plain` share actions.
- [ ] Shared social text extracts a URL correctly.
- [ ] Manual pasted URL flow works.
- [ ] Platform detection works for required hosts.
- [ ] At least one public URL can be analyzed through the selected yt-dlp runtime.
- [ ] Media metadata is displayed through app-owned models.
- [ ] Video download works on at least one verified public source.
- [ ] Audio-only download works on at least one verified public source.
- [ ] FFmpeg merge/post-processing is verified when required.
- [ ] Active download progress is visible.
- [ ] Download cancellation works.
- [ ] Finished file is visible in `Downloads/SocialVideoDownloader/` or the documented equivalent MediaStore destination.
- [ ] Completion/failure notification works.
- [ ] Unit tests pass.
- [ ] Lint passes or any exception is explicitly documented and narrowly justified.
- [ ] `assembleDebug` passes.
- [ ] GitHub Actions uploads a debug APK Artifact.
- [ ] No credentials or signing keys are committed.
- [ ] `THIRD_PARTY_NOTICES.md` exists.
- [ ] README is updated to match actual behavior.
- [ ] Known upstream/runtime limitations are listed in the PR.
- [ ] No tag or formal GitHub Release has been created.

---

## 22. Required implementation report

When Codex finishes, return a concise report containing:

```text
Issue:
Branch:
PR:
Head commit:

Build:
Unit tests:
Lint:
assembleDebug:
CI:
Artifact:

Runtime:
youtubedl-android:
yt-dlp bundled/runtime version:
FFmpeg:
ABIs:

Manual verification:
- Share Intent:
- URL extraction:
- YouTube analyze:
- Video download:
- Audio download:
- Cancel:
- Downloads visibility:
- Notifications:
- Additional social platform:

Known limitations:
Release created: NO
```

Do not report a capability as passing unless it was actually exercised or covered by an appropriate test.

---

## 23. Stop conditions / escalation

Stop and report instead of silently changing architecture if any of these occur:

1. `youtubedl-android 0.18.1` cannot initialize or build under the selected current Android toolchain.
2. Native libraries fail on arm64 / modern 16 KB page-size devices.
3. FFmpeg integration requires a materially different licensing/build strategy.
4. Scoped-storage limitations prevent the required Downloads behavior.
5. A required solution would need broad storage, accessibility, overlay, device-admin, or other invasive permissions.
6. The implementation would require committing secrets or production signing materials.
7. CI cannot build a reproducible debug APK without undocumented local files.

In these cases, preserve the smallest working implementation, document evidence, and request an owner decision in the PR rather than adding an unsafe workaround.

---

## 24. Owner decisions already fixed by this contract

Do not ask again unless implementation evidence requires a change:

- Android is a separate repository from the Windows app.
- Android is the current mobile priority; iOS is not in scope.
- Kotlin + Jetpack Compose is the chosen UI stack.
- Explicit Android Sharesheet sharing is the primary mobile workflow.
- Share action analyzes automatically but does **not** auto-download in v0.1.0.
- No clipboard/background spying approach.
- CI debug APK Artifact is desired.
- Formal Release is **not authorized** by this contract.
- v0.1.0 is the first Android milestone.
