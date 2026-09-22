# Development Guide

本文件整理 **Social Video Downloader for Android** 的開發、建置、測試與發佈相關資訊。一般使用者請先閱讀根目錄的 [README.md](../README.md)。

## 技術基線

- **語言：** Kotlin
- **UI：** Jetpack Compose / Material 3
- **最低 Android 版本：** Android 8.0 (API 26)
- **targetSdk / compileSdk：** API 36
- **JDK：** 17
- **Android Gradle Plugin：** 8.9.1+
- **核心 Runtime：** youtubedl-android、yt-dlp、FFmpeg
- **支援 ABI：** arm64-v8a、armeabi-v7a、x86_64、x86

實際 dependency 與版本請以 repository 內的 Gradle 設定為準。

## 取得原始碼

```bash
git clone https://github.com/charleswoo1/video_downloader_android.git
cd video_downloader_android
```

請確認已安裝 Android SDK，並設定：

- `JAVA_HOME`：JDK 17
- `ANDROID_HOME`：Android SDK

## 本機驗證

Windows：

```powershell
.\gradlew.bat test
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Linux / macOS：

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Debug APK 預設輸出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如需透過 adb 安裝：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions / CI

目前 Android CI 採 **review-gated manual workflow**，不會因每次 commit 自動執行。

標準流程：

1. 從最新 `main` 建立 feature / fix branch。
2. 完成程式碼與本機測試。
3. Review 通過後，手動執行 `Android CI`。
4. CI 執行 unit tests、lint、Debug APK build。
5. 下載 CI Artifact 進行實機驗證。
6. 實機驗證完成後才建立 PR / merge。
7. 正式 Release 必須由 repository owner 明確授權。

CI Artifact 是測試產物，與正式 GitHub Release 不同。

## 專案架構概覽

主要模組包括：

- **UI / Compose**：主畫面、設定、登入與下載狀態。
- **Platform detection / routing**：依 URL 選擇對應解析引擎。
- **Native extractors**：Instagram、Threads、X 等平台的原生解析流程。
- **yt-dlp fallback**：處理其他支援來源與 fallback 情境。
- **Authenticated sessions**：Instagram、Threads、X 的 WebView 登入與 Session 管理。
- **Download service**：Foreground Service、進度通知與檔案儲存。
- **Runtime diagnostics**：Engine trace 與 runtime 診斷；正式 UI 預設隱藏，可由設定開啟除錯資訊。

## 登入與敏感資料

開發或測試時禁止把以下資料提交到 repository、Issue、PR、Log fixture：

- Cookie / cookies.txt
- auth token / session token
- 帳號密碼
- API key
- Android signing keystore
- keystore password
- `local.properties`
- 裝置識別資訊

平台 Session 的 production 儲存與隔離規則不得因測試方便而繞過。

## Release 準備

正式版本發佈前至少確認（完整流程見 [RELEASE.md](RELEASE.md)）：

1. `versionName` / `versionCode` 正確。
2. Unit tests、lint、assemble 全部通過。
3. Release branch 的 Manual Debug CI PASS。
4. Release preparation PR 完成 review 後 merge 到 `main`。
5. 從 `main` 執行 Signed Release Candidate workflow 並 PASS。
6. Release candidate 已完成實機驗證。
7. README、CHANGELOG、SECURITY、THIRD_PARTY_NOTICES、LICENSE 與版本資訊一致。
8. Release APK 命名與 checksum 已確認。
9. 確認 RC 後 `main` 沒有新增變更；tag 必須指向已驗證 RC 的同一 commit。
10. 建立 tag 與 GitHub Release 前取得 repository owner 明確授權。

> **重要：** `release-candidate.yml` 為 manual `workflow_dispatch`。v1.0.0 的正式流程是先將已 review 的 release-prep PR merge 至 default branch `main`，再從 `main` 產生 signed RC；RC 驗證通過前不得建立正式 tag / Release。

本專案採 **GNU GPL v3.0 (GPL-3.0)**，完整條款見根目錄 [LICENSE](../LICENSE)。第三方元件授權與來源見 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

v1.0.0 採：

- `versionName = "1.0.0"`
- `versionCode = 10000`
- APK：`SocialVideoDownloader-Android-v1.0.0.apk`
- checksum：`SHA256SUMS.txt`
- tag：`v1.0.0`

## 其他開發文件

- [CONTRIBUTING.md](../CONTRIBUTING.md)：貢獻流程與 coding guidelines
- [AGENTS.md](../AGENTS.md)：AI coding agent 工作規則
- [SECURITY.md](../SECURITY.md)：安全政策
- [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)：第三方軟體與授權
- [RELEASE.md](RELEASE.md)：正式版本簽章、RC 與發佈流程
- [CHANGELOG.md](../CHANGELOG.md)：版本變更紀錄
- [docs/handoffs/](handoffs/)：歷史 handoff / implementation contracts
- [docs/references/](references/)：上游與實作參考
- [docs/research/](research/)：研究記錄
- [docs/regression/](regression/)：實機 regression 資料
