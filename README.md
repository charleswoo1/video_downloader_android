# Social Video Downloader for Android

Android 版多平台社群影音下載工具。

本專案讓使用者可直接從 **YouTube、Facebook、Instagram、Threads、X (Twitter)、TikTok** 等 App 的「分享」選單，把貼文／影片網址分享至 **Social Video Downloader**，由 App 自動擷取網址、解析內容並下載到 Android 裝置。

> **注意：** 本專案目前的 `v0.1.0` 處於開發階段（Pre-release / Development），尚未由 repository 擁有者正式授權建立正式 GitHub Release。可透過 GitHub Actions CI 下載測試用 Debug APK。

---

## 核心體驗

```text
社群 App
  ↓ 分享 (Share to...)
Social Video Downloader
  ↓
自動擷取 HTTP/HTTPS 網址
  ↓
辨識平台並使用 yt-dlp／原生解析器取得媒體資訊
  ↓
顯示縮圖、標題、長度與畫質選項 (或僅音訊)
  ↓
確認下載 (由前台服務執行，顯示進度與通知)
  ↓
自動儲存至 公用下載目錄/SocialVideoDownloader/
  ↓
發送下載完成通知
```

除了 Android 系統分享（Sharesheet），也支援直接在主畫面貼上網址進行解析與下載。

---

## 系統需求與環境基線

- **最低系統版本 (minSdk)：** Android 8.0 (API Level 26)
- **目標系統版本 (targetSdk)：** Android 16 (API Level 36)
- **編譯系統版本 (compileSdk)：** Android 16 (API Level 36)
- **建置工具：** JDK 17, Gradle 8.11+, Android Gradle Plugin 8.9.1+ (官方支援 API 36)
- **開發語言與 UI：** Kotlin, Jetpack Compose, Material 3
- **核心 Runtime：** `youtubedl-android 0.18.1` (包含 `yt-dlp` 與 `FFmpeg`)
- **支援架構 (ABIs)：** `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`

---

## 本機編譯與安裝

### 1. 取得專案原始碼

```bash
git clone https://github.com/charleswoo1/video_downloader_android.git
cd video_downloader_android
```

### 2. 環境變數設定

確保已設定 `JAVA_HOME`（指向 JDK 17）與 `ANDROID_HOME`（指向 Android SDK）。

### 3. 執行單元測試與 Lint 檢查

在 Windows (PowerShell / CMD)：

```powershell
.\gradlew.bat test
.\gradlew.bat lintDebug
```

在 Linux / macOS：

```bash
./gradlew test
./gradlew lintDebug
```

### 4. 建置 Debug APK

```powershell
.\gradlew.bat assembleDebug
```

建置完成後，APK 將產出於：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 5. 安裝到 Android 裝置或模擬器

使用 `adb` 進行安裝：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 測試 APK 下載 (CI Artifacts)

每次 Pull Request 與 Push 到 `main` 時，GitHub Actions CI 會自動執行測試、Lint 並打包 Debug APK。

1. 前往 GitHub 專案頁面的 [Actions](https://github.com/charleswoo1/video_downloader_android/actions) 分頁。
2. 點選最新一次成功的 CI 工作階段 (`Android CI`)。
3. 在頁面底部的 **Artifacts** 區塊下載 `SocialVideoDownloader-Android-CI-v0.1.0-run-<編號>`。
4. 解壓縮後即可取得 `SocialVideoDownloader-Android-v0.1.0-debug.apk`。

---

## 使用方式

### 方式一：從社群 App 分享
1. 在 YouTube、Instagram、TikTok、Facebook、X 或 Threads 等 App 中，點擊「分享」。
2. 在分享選單中選取 **Social Video Downloader**。
3. App 將自動開啟、擷取文字中的第一個有效網址並開始解析。
4. 解析完成後，選擇您偏好的畫質（如 1080p、720p、最佳畫質）或「僅音訊」，點擊「開始下載」。

### 方式二：手動輸入或貼上網址
1. 開啟 **Social Video Downloader**。
2. 在主畫面輸入框直接貼上影片網址，點擊「解析網址」。
3. 解析完成後選擇下載選項並點擊「開始下載」。

---

## 檔案儲存位置

下載完成的影片或音訊檔案將自動儲存於 Android 系統公用儲存空間：

```text
Downloads/SocialVideoDownloader/
```

- **Android 10+ (API 29+)：** 使用 Android 原生 `MediaStore.Downloads` 規範儲存，並自動於媒體庫中建立索引。
- **Android 8.0 - 9.0 (API 26 - 28)：** 儲存至公共下載目錄並自動觸發 MediaScanner 廣播。

---

## 平台支援注意事項與已知限制

- **未保證所有網站皆可下載：** 社群平台演算法與頁面結構頻繁更新，部分平台（如 Facebook 私密社團、Instagram 需登入之限制內容等）可能無法下載或解析失敗。
- **登入與 Cookie：** `v0.1.0` 目前不支援帳號登入或匯入 Cookie；需要登入之成人或私人內容將回傳錯誤提示。
- **DRM 保護內容：** 本工具嚴格遵守原則，不提供任何 DRM 繞過或破解功能。
- **非單一檔案 / 播放清單：** 目前版本僅支援單一影片／音訊下載，尚未支援播放清單批次佇列。
- **Threads 畫質選項：** `v0.1.0` 僅提供「最佳畫質」與「僅音訊」，避免在 Threads 頁面資料無穩定畫質對應時顯示無法保證的 1080p／720p 選項。
- **16 KB page size：** 目前 `youtubedl-android 0.18.1` 內含的部分 FFmpeg/WebP native library 仍可能只有 4 KB ELF alignment；在採用 16 KB memory page 的 Android 裝置上，FFmpeg 合併／轉檔功能尚未宣告相容，正式廣泛發布前仍需上游修正或實機驗證。

---

## 與 Windows 版的關係

本專案與 Windows 版影音下載器屬於同一產品線，但採用獨立原生架構：

- **Android 版：** Kotlin / Jetpack Compose / youtubedl-android
- **Windows 版：** https://github.com/charleswoo1/video_downloader (Python / CustomTkinter / yt-dlp)

---

## 授權與協力廠商聲明

詳細使用的開源元件（包含 GPL-3.0 的 `youtubedl-android`、Unlicense 的 `yt-dlp`、LGPL/GPL 的 `FFmpeg` 與 Apache-2.0 的 AndroidX 等）請參閱 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

---

## 專案貢獻與規則

- 開發規則：請參閱 [`AGENTS.md`](AGENTS.md)
- 貢獻指南：請參閱 [`CONTRIBUTING.md`](CONTRIBUTING.md)
- 安全政策：請參閱 [`SECURITY.md`](SECURITY.md)
