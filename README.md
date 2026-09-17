# Social Video Downloader for Android

Android 版多平台社群影音下載工具。

本專案目標是讓使用者可直接從 **YouTube、Facebook、Instagram、Threads、X、TikTok** 等 App 的「分享」選單，把貼文／影片網址分享至 **Social Video Downloader**，由 App 自動擷取網址、解析內容並下載到 Android 裝置。

> 專案目前處於初始化階段，尚未提供可安裝 APK。第一個開發里程碑預計為 `v0.1.0`。

## 開發狀態

Android `v0.1.0` 的第一階段實作規格已固定於：

[`docs/handoffs/HANDOFF_ANDROID_V0.1.0.md`](docs/handoffs/HANDOFF_ANDROID_V0.1.0.md)

該文件是目前交給 Codex / coding agent 的主要實作合約；正式 Release 仍需 repository owner 另外明確授權。

## 預計核心體驗

```text
社群 App
  ↓ 分享
Social Video Downloader
  ↓
自動擷取 URL
  ↓
辨識平台並解析媒體資訊
  ↓
選擇影片畫質 / 音訊
  ↓
下載並顯示進度
  ↓
儲存至 Android 裝置
```

除了 Android Sharesheet，主畫面也預計支援直接貼上網址後解析與下載。

## 預計技術方向

- Kotlin
- Jetpack Compose
- Android Share Intent (`ACTION_SEND` / `text/plain`)
- yt-dlp Android runtime
- FFmpeg
- Android MediaStore / 系統儲存機制
- 背景下載與系統通知

目前 v0.1.0 handoff 基線為 `compileSdk 36` / `targetSdk 36` / `minSdk 26`；實際 dependency 與 runtime 版本須以實作 PR 驗證結果為準。

## 第一階段目標（v0.1.0）

- [ ] 建立原生 Android App 骨架
- [ ] 可從其他 App 分享文字／網址至本 App
- [ ] 從分享內容自動擷取 HTTP/HTTPS URL
- [ ] 支援常見社群平台辨識
- [ ] 解析影片基本資訊
- [ ] 選擇影片畫質或音訊模式
- [ ] 下載與進度顯示
- [ ] FFmpeg 音視訊合併
- [ ] 儲存至 Android 裝置
- [ ] 下載完成通知
- [ ] 主畫面直接貼網址
- [ ] 建立可下載測試 APK 的 CI Artifact

## 與 Windows 版的關係

Windows 版本位於：

https://github.com/charleswoo1/video_downloader

兩個 repository 屬於同一產品系列，但採用不同的原生技術與獨立版本線：

- Windows：Python / CustomTkinter / yt-dlp
- Android：Kotlin / Jetpack Compose / Android 原生元件

Android 版不直接移植 Windows UI 或 Windows 專屬執行環境；可共用的是功能規格、平台行為與測試案例。

## 專案原則

- `main` 保持可建置、可測試狀態。
- 功能開發原則上透過 Issue → branch → Pull Request → merge。
- Release 與正式版本 tag 必須明確建立，不以一般 CI Artifact 取代正式 Release。
- 未經專案擁有者明確要求，不自動建立正式 Release。
- 不提交 Cookie、帳號 Token、API Key、簽章金鑰或其他敏感資訊。

## 使用與內容權利

本工具只應用於使用者有權存取、下載或保存的內容。網站規則、登入狀態、地區限制、DRM 與平台政策都可能影響實際可用性。本專案不以繞過 DRM 或其他存取控制為目標。
