# Third-Party Software Notices and Licenses

This project incorporates third-party open-source components, libraries, and binary runtimes.
This document provides notice of these dependencies, their upstream repositories, and their applicable licenses.

---

## 1. youtubedl-android

- **Description:** Android wrapper library bundling `yt-dlp` executable and Python runtime.
- **Upstream Repository:** https://github.com/yausername/youtubedl-android
- **Active Maintainer Fork:** https://github.com/junkfood02/youtubedl-android
- **Pinned Upstream Contributions / Branches:**
  - **PR #361** (`76ed1bf9eb6ed2e6858ed88a4ccfbb8a35f292d7`): Modern runtime upgrade integrating Python 3.14, curl_cffi 0.16.2, cffi, FFmpeg 8.1.2, and QuickJS-ng.
  - **PR #359** (`a505022ad858dcf947ecce56b49a97d5b906fe46`): Deterministic `yt-dlp` packaged binary release `2026.08.30.232658` (SHA256: `3f1b267b4488f3aed3731a9e84a44011ca5569901868532e10ee11fd07d69707`).
- **License:** GNU General Public License v3.0 (GPL-3.0)
- **License URL:** https://github.com/yausername/youtubedl-android/blob/master/LICENSE

## 2. yt-dlp

- **Description:** Command-line audio/video downloader bundled within youtubedl-android.
- **Repository:** https://github.com/yt-dlp/yt-dlp
- **Packaged Version:** `2026.08.30.232658`
- **License:** The Unlicense
- **License URL:** https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE

## 3. curl_cffi

- **Description:** Python binding for curl-impersonate via cffi, enabling modern HTTP/2, TLS impersonation, and Cloudflare/WAF bypass compatibility.
- **Repository:** https://github.com/lexiforest/curl_cffi
- **Bundled Version:** 0.16.2
- **License:** MIT License
- **License URL:** https://github.com/lexiforest/curl_cffi/blob/main/LICENSE

## 4. QuickJS-ng

- **Description:** Embedded JavaScript engine fork designed for modern JavaScript support, bundled in youtubedl-android for YouTube JavaScript challenges.
- **Repository:** https://github.com/quickjs-ng/quickjs
- **License:** MIT License
- **License URL:** https://github.com/quickjs-ng/quickjs/blob/master/LICENSE

## 5. FFmpeg

- **Description:** Complete, cross-platform solution to record, convert, and stream audio and video. Bundled within youtubedl-android ffmpeg artifact.
- **Repository:** https://ffmpeg.org / https://github.com/FFmpeg/FFmpeg
- **Packaged Version:** 8.1.2 (built with 16 KB ELF page-alignment compatibility)
- **License:** GNU Lesser General Public License (LGPL) version 2.1+ / GNU General Public License (GPL) version 2+ (depending on build configuration)
- **License URL:** https://www.ffmpeg.org/legal.html

## 6. tribixbite/yt-dlp-threads (Reference)

- **Description:** Threads extraction reference architecture utilizing Googlebot crawler user-agent and lightweight HTML/JSON metadata extraction.
- **Repository:** https://github.com/tribixbite/yt-dlp-threads
- **License:** MIT License
- **License URL:** https://github.com/tribixbite/yt-dlp-threads/blob/master/LICENSE

## 7. AndroidX & Jetpack Compose

- **Description:** Android Jetpack libraries and Jetpack Compose UI toolkit.
- **Provider:** The Android Open Source Project / Google LLC
- **License:** Apache License 2.0
- **License URL:** https://www.apache.org/licenses/LICENSE-2.0

## 8. Coil (Coroutine Image Loader)

- **Description:** Image loading library for Android and Compose.
- **Repository:** https://github.com/coil-kt/coil
- **Artifact:** `io.coil-kt:coil-compose:2.7.0`
- **License:** Apache License 2.0
- **License URL:** https://github.com/coil-kt/coil/blob/main/LICENSE.txt

## 9. Kotlin & KotlinX Coroutines

- **Description:** Kotlin programming language and asynchronous coroutines libraries.
- **Repository:** https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines
- **License:** Apache License 2.0
- **License URL:** https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt

---

> [!NOTE]
> Public production releases are gated until overall project repository licensing is formally determined by the project owner.
