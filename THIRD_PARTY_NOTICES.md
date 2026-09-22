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

## 6. tribixbite/yt-dlp-threads

- **Description:** Threads extractor plugin for yt-dlp utilizing link-preview crawler user-agent and embedded JSON metadata parsing.
- **Repository:** https://github.com/tribixbite/yt-dlp-threads
- **Role:** Bundled runtime plugin
- **Pinned Commit:** `c4c44141cb10715f94296a808f5d89a0d24dfe94`
- **Source File:** `yt_dlp_plugins/extractor/threads.py`
- **SHA256:** `c28e410b69a0c2377c8530b36f6dca4b973484855b42e281846b97b3305b28ba`
- **License:** The Unlicense / Public Domain
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

## 10. 2Xsave/insave

- **Description:** Reference implementation for Native Instagram extraction, URL normalization, and target post isolation.
- **Repository:** https://github.com/2Xsave/insave
- **Pinned Commit:** `6454affbd8e7960db012c81ffc1c213b75e8947b`
- **Role:** Direct engineering reference ported to Kotlin (`NativeInstagramEngine`).
- **License:** MIT License
- **License URL:** https://github.com/2Xsave/insave/blob/main/LICENSE

## 11. 2Xsave/trsave

- **Description:** Reference implementation for Native Threads extraction, recursive media discovery, and share URL canonicalization.
- **Repository:** https://github.com/2Xsave/trsave
- **Pinned Commit:** `2841945254c24c1ad3f3658410788503bcdced72`
- **Role:** Direct engineering reference ported to Kotlin (`NativeThreadsEngine`).
- **License:** MIT License
- **License URL:** https://github.com/2Xsave/trsave/blob/main/LICENSE

## 12. 2Xsave/twsave

- **Description:** Reference implementation for Native X / Twitter extraction, Bearer/guest token discovery, GraphQL endpoint queries, and HTML fallback.
- **Repository:** https://github.com/2Xsave/twsave
- **Pinned Commit:** `0da4dd7db8a0f93445338821309cff736c79b9ec`
- **Role:** Direct engineering reference ported to Kotlin (`NativeXEngine`).
- **License:** MIT License
- **License URL:** https://github.com/2Xsave/twsave/blob/main/LICENSE

## 13. 2Xsave/2xsave_common

- **Description:** Shared HTTP session profiles, coherent browser identity, and retry policy references.
- **Repository:** https://github.com/2Xsave/2xsave_common
- **Pinned Commit:** `1d52f56aa214a3c4e3c965d480b52a08b1643281`
- **Role:** Direct engineering reference ported to Kotlin (`PlatformHttpSession`, `BrowserIdentity`, `RequestProfile`, `RetryPolicy`).
- **License:** MIT License
- **License URL:** https://github.com/2Xsave/2xsave_common/blob/main/LICENSE

## 14. 2Xsave/2XsaveTUI

- **Description:** Multi-engine platform dispatch architecture and domain matching logic.
- **Repository:** https://github.com/2Xsave/2XsaveTUI
- **Pinned Commit:** `301b6992bb5aa44c8a374ef1125981c77692de56`
- **Role:** Architecture reference for multi-platform engine routing.
- **License:** MIT License
- **License URL:** https://github.com/2Xsave/2XsaveTUI/blob/main/LICENSE

---

---

## Project License

This project's original source code is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the repository's top-level [LICENSE](LICENSE) file for the complete license text.

Third-party components remain subject to their respective licenses documented above. Public binary releases must continue to satisfy the applicable redistribution and corresponding-source obligations of bundled components. Each production release must point to the exact matching Git tag/source revision.

> [!NOTE]
> GPL-licensed repositories used only for architectural research (InstaDownload, YTDLnis, Seal, NewPipeExtractor) remain reference-only; no source code from those repositories is intentionally copied or directly translated into this repository.
