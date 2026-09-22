# Changelog

所有重要變更都記錄於此文件。

## [1.0.0] - Unreleased

第一個正式版本。

### 使用體驗

- 支援從 Android 分享選單直接將社群貼文 / 影片交給 Social Video Downloader。
- 支援在 App 主畫面手動貼上網址並解析。
- 提供影片畫質與僅音訊選項（依來源實際可用格式而定）。
- 下載使用前台服務執行，提供進度與完成通知。
- 下載檔案儲存於 `Downloads/SocialVideoDownloader/`。

### 平台支援

- YouTube
- Facebook
- Instagram
- Threads
- X (Twitter)
- TikTok
- 其他可由 yt-dlp 支援的來源（不保證所有網站皆可用）

### 登入與受限內容

- Instagram WebView 登入。
- Threads WebView 登入。
- X (Twitter) WebView 登入。
- Session 狀態驗證與重新登入流程。
- Session / Cookie 僅儲存於裝置本機。

### 穩定性與維護

- Instagram / Threads / X 採原生解析與 fallback 路由。
- 新增 Runtime Diagnostics 與 Engine Trace。
- 一般使用預設隱藏診斷資訊，可在「設定 → 進階 → 除錯資訊」開啟。
- 主畫面會在平台尚未登入時提示使用者前往設定。
- 完成多輪 unit test、lint、CI 與實機 regression 驗證。

### 已知限制

- 不支援 DRM 繞過。
- 平台頁面 / API 更新可能造成個別內容暫時無法解析。
- 私人、地區限制、已刪除或需要額外平台安全驗證的內容仍可能無法下載。
