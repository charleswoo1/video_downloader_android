# Social Video Downloader for Android

在 Android 上直接從社群 App 分享影片網址，快速解析並下載到手機。

支援 **YouTube、Facebook、Instagram、Threads、X (Twitter)、TikTok** 等常見平台；也可以直接在 App 內貼上網址使用。

## 主要功能

- **直接從 Android 分享選單下載**：在社群 App 點「分享」，選擇 Social Video Downloader 即可開始解析。
- **支援手動貼上網址**：不透過分享功能也能直接使用。
- **多平台解析**：支援 YouTube、Facebook、Instagram、Threads、X、TikTok 等來源。
- **Instagram / Threads / X 登入支援**：可在 App 內登入，用於需要帳號權限、年齡限制或其他受限內容。
- **畫質與音訊選擇**：依來源提供可用的影片畫質或僅音訊選項。
- **背景下載與進度通知**：下載期間可離開 App，完成後會收到通知。
- **自動儲存至 Android Downloads**：下載內容集中存放於 `Downloads/SocialVideoDownloader/`。
- **本機 Session 儲存**：登入憑證只保留在裝置本機，不會由本 App 上傳到外部伺服器。
- **內建除錯模式**：一般使用預設隱藏診斷資訊，需要排查問題時可在設定中開啟。

## 系統需求

- Android 8.0（API 26）或更新版本。
- 需要網路連線。
- 部分平台或內容需要登入對應帳號。

## 下載與安裝

正式版本請從 GitHub 的 **Releases** 頁面下載 APK：

[前往 Releases](https://github.com/charleswoo1/video_downloader_android/releases)

下載 APK 後，如果 Android 顯示「不允許安裝未知來源 App」，請依系統提示授權目前使用的瀏覽器或檔案管理器安裝 APK。

> 發佈前的 GitHub Actions Artifact 屬於測試版本，不建議一般使用者安裝。

## 使用方式

### 方法一：從社群 App 分享

1. 在 YouTube、Instagram、Threads、X、TikTok、Facebook 等 App 開啟想下載的內容。
2. 點擊 **分享**。
3. 在 Android 分享選單選擇 **Social Video Downloader**。
4. App 會自動取得分享內容中的網址並開始解析。
5. 解析完成後選擇可用畫質或音訊選項。
6. 點擊下載，完成後檔案會存入 Android Downloads。

### 方法二：直接貼上網址

1. 開啟 **Social Video Downloader**。
2. 將影片或貼文網址貼到主畫面。
3. 點擊解析。
4. 選擇下載選項後開始下載。

## 平台登入

公開內容通常可以直接嘗試解析；如果內容需要登入、受到年齡限制或平台要求帳號驗證，可使用 App 內建登入功能。

1. 點擊主畫面右上角的 **設定**。
2. 在「平台登入」查看 Instagram、Threads、X 的登入狀態。
3. 點擊 **管理平台登入**。
4. 選擇平台並完成登入。
5. 登入成功後，App 會保存該平台的 Session 供後續下載使用。

若 Session 失效，可回到同一位置重新登入。

### 隱私與安全

- Session / Cookie 儲存在 Android 裝置本機的安全儲存機制中。
- App 不會把登入 Cookie 上傳到開發者伺服器。
- 不需要登入的平台不必設定帳號。
- 若不再需要某個帳號 Session，可以在平台登入設定中清除。

## 下載檔案位置

下載完成的影片或音訊預設存放於：

```text
Downloads/SocialVideoDownloader/
```

可直接使用 Android 的「檔案」或其他檔案管理 App 開啟。

## 支援平台

| 平台 | 分享下載 | App 內登入 | 備註 |
| --- | --- | --- | --- |
| YouTube | ✅ | — | 依來源提供可用畫質 / 音訊 |
| Facebook | ✅ | — | 私密或受限內容可能無法解析 |
| Instagram | ✅ | ✅ | 支援需要登入的內容 |
| Threads | ✅ | ✅ | 支援平台 Session |
| X (Twitter) | ✅ | ✅ | 支援年齡限制 / 需要登入的內容 |
| TikTok | ✅ | — | 實際可用性依來源內容而定 |

社群平台會持續更新網頁與 API，因此個別貼文仍可能因平台限制、內容權限或服務變更而暫時無法下載。

## 設定與除錯資訊

一般使用時不需要開啟除錯資訊。

如遇到解析問題，需要提供更多技術資訊協助排查時，可前往：

**設定 → 進階 → 除錯資訊**

開啟後，主畫面會額外顯示引擎路由與 Runtime Diagnostics。關閉後這些資訊會再次隱藏，診斷架構本身仍會保留。

## 已知限制

- 不提供 DRM 保護內容的繞過或破解。
- 不保證所有公開網址或所有平台內容都能下載。
- 私人、已刪除、地區限制、年齡限制或平台額外安全驗證內容可能仍受來源平台限制。
- 目前主要使用情境為單一貼文 / 單一影片下載，不以播放清單批次下載為主要功能。
- 社群平台更新後若解析方式失效，需要等待 App 更新。

## 使用責任

請僅下載你有權存取與保存的內容，並遵守所在地法律、來源網站條款及著作權規範。

本專案不提供 DRM 破解，也不代表取得內容著作權人或平台的授權。

## Windows 版本

Windows 版本為獨立專案：

[Social Video Downloader for Windows](https://github.com/charleswoo1/video_downloader)

## 問題回報

若遇到無法解析或下載的網址，建議提供：

- 平台名稱
- App 版本
- 錯誤訊息
- 是否已登入
- 問題是否可以穩定重現

**請勿在 Issue 中貼出 Cookie、Token、密碼或其他登入憑證。**

需要更完整的診斷時，可以先開啟 App 的「除錯資訊」，但提交前仍應確認內容不包含私人資料。

## 文件

一般使用者通常只需要本 README。

開發與維護相關資訊已移至：

- [Development Guide](docs/DEVELOPMENT.md)
- [Contributing](CONTRIBUTING.md)
- [Security Policy](SECURITY.md)
- [Third-Party Notices](THIRD_PARTY_NOTICES.md)

## 第三方軟體與授權

本專案整合多個開源元件，包括 yt-dlp、FFmpeg、AndroidX / Jetpack Compose 等。

完整清單與授權資訊請參閱 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
