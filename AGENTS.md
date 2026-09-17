# AGENTS.md

本檔案定義 AI coding agents（包含 Codex）在此 repository 的預設工作規則。

## Repository scope

本 repository 僅負責 **Social Video Downloader for Android**。

Windows 版本位於 `charleswoo1/video_downloader`，除非任務明確要求跨 repo 修改，否則不要改動 Windows repository。

## Source of truth

- GitHub repository 內容是專案主要來源。
- 執行任務前先讀取目前 `main`、相關 Issue / PR、README 與本檔案。
- 不依賴聊天摘要取代 repository 現況。

## Development workflow

一般功能與 bugfix 採用：

1. 確認或建立 Issue。
2. 從最新 `main` 建立 feature/fix branch。
3. 完成實作與測試。
4. 建立 Pull Request。
5. CI 必須通過。
6. Review 後再 merge。

除非任務明確允許，避免直接把功能 commit 到 `main`。

## Android baseline

- 使用 Kotlin。
- UI 使用 Jetpack Compose。
- 優先採用 Android 官方 API 與 lifecycle-aware 元件。
- 分享入口使用標準 Android Intent / Sharesheet 機制。
- 下載、解析與 UI 狀態應分層，避免將 yt-dlp 或 FFmpeg 呼叫直接寫進 Composable。
- 外部 dependency 必須有明確用途，避免不必要的大型框架。

具體 SDK / Gradle / dependency 版本以 repository 內實際設定為準；新增或升級版本時需確認相容性與 build 狀態。

## Security and privacy

禁止提交：

- Cookie / cookies.txt
- 帳號 Token
- API key
- Android signing keystore
- keystore password
- `local.properties`
- 個人裝置識別資訊
- 其他 credentials / secrets

登入或 Cookie 功能若日後加入，必須使用 Android 適合的安全儲存方式，不可照搬 Windows 瀏覽器 Cookie 讀取流程。

## Download behavior

- 不實作 DRM 繞過。
- 對平台限制、登入限制或無法解析的來源應回傳可理解的錯誤，不以不安全 workaround 規避。
- 分享內容可能包含文字與多個 URL；URL parser 必須可測試且不可假設輸入一定是純網址。

## Testing

新增核心功能時至少涵蓋對應測試，特別是：

- URL extraction
- platform detection
- shared-text parsing
- download request / state mapping
- error handling

若修改 build、Gradle、CI 或下載 runtime，PR 必須證明 debug APK 可成功建置。

## GitHub Actions

GitHub Actions 可用於：

- 編譯 / lint / unit test
- 建立測試 APK Artifact
- 明確授權後的正式 Release build

一般 CI 不應自動建立正式 GitHub Release。

## Releases

- Android 採獨立版本線，初始開發版本由 `v0.1.0` 開始。
- CI Artifact 與正式 Release 必須區分。
- 未經 repository owner 明確要求，不建立 tag、不發布正式 Release、不覆寫既有 Release。
- 正式發布前必須確認 versionName / versionCode、測試結果與產物名稱。

## Change discipline

- 保持修改範圍與任務一致。
- 不順便大規模重構無關程式碼。
- 不刪除既有功能或文件，除非任務明確要求或有充分理由並於 PR 說明。
- 遇到架構取捨時，優先保留可測試性、可維護性與 Android 原生相容性。
