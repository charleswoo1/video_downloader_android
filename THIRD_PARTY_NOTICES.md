# Third-Party Software Notices and Licenses

This project incorporates third-party open-source components, libraries, and binary runtimes.
This document provides notice of these dependencies, their upstream repositories, and their applicable licenses.

---

## 1. youtubedl-android

- **Description:** Android wrapper library bundling the `yt-dlp` executable and Python runtime.
- **Repository:** https://github.com/junkfood02/youtubedl-android (fork of https://github.com/yausername/youtubedl-android)
- **Artifacts:**
  - `io.github.junkfood02.youtubedl-android:library:0.18.1`
  - `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`
- **License:** GNU General Public License v3.0 (GPL-3.0)
- **License URL:** https://github.com/junkfood02/youtubedl-android/blob/master/LICENSE

## 2. yt-dlp

- **Description:** Command-line audio/video downloader bundled within youtubedl-android.
- **Repository:** https://github.com/yt-dlp/yt-dlp
- **License:** The Unlicense
- **License URL:** https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE

## 3. FFmpeg

- **Description:** Complete, cross-platform solution to record, convert, and stream audio and video. Bundled within youtubedl-android ffmpeg artifact.
- **Repository:** https://ffmpeg.org / https://github.com/FFmpeg/FFmpeg
- **License:** GNU Lesser General Public License (LGPL) version 2.1+ / GNU General Public License (GPL) version 2+ (depending on build configuration)
- **License URL:** https://www.ffmpeg.org/legal.html

## 4. AndroidX & Jetpack Compose

- **Description:** Android Jetpack libraries and Jetpack Compose UI toolkit.
- **Provider:** The Android Open Source Project / Google LLC
- **License:** Apache License 2.0
- **License URL:** https://www.apache.org/licenses/LICENSE-2.0

## 5. Coil (Coroutine Image Loader)

- **Description:** Image loading library for Android and Compose.
- **Repository:** https://github.com/coil-kt/coil
- **Artifact:** `io.coil-kt:coil-compose:2.7.0`
- **License:** Apache License 2.0
- **License URL:** https://github.com/coil-kt/coil/blob/main/LICENSE.txt

## 6. Kotlin & KotlinX Coroutines

- **Description:** Kotlin programming language and asynchronous coroutines libraries.
- **Repository:** https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines
- **License:** Apache License 2.0
- **License URL:** https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt

---

> [!NOTE]
> Public production releases are gated until overall project repository licensing is formally determined by the project owner.
