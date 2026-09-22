# v1.0.0 Release Guide

本文件定義 Social Video Downloader for Android 正式 `v1.0.0` 的發佈流程。

## Release identity

- **Version name:** `1.0.0`
- **Version code:** `10000`
- **Git tag:** `v1.0.0`
- **GitHub Release title:** `v1.0.0`
- **APK:** `SocialVideoDownloader-Android-v1.0.0.apk`
- **Checksum:** `SHA256SUMS.txt`

Version code 採：

```text
major * 10000 + minor * 100 + patch
```

例如：

- 1.0.0 → 10000
- 1.0.1 → 10001
- 1.1.0 → 10100
- 2.0.0 → 20000

## Release gates

建立正式 tag / GitHub Release 前，全部條件必須成立：

1. Release branch 與預定 release 內容已完成 review。
2. `./gradlew test` PASS。
3. `./gradlew lintDebug` PASS。
4. Release branch 的 Manual Debug CI PASS。
5. Release preparation PR 已 merge 到 `main`，且 `main` 指向預定的 release commit。
6. 從 `main` 手動執行 **Android Release Candidate** workflow，signed APK build PASS。
7. Owner-device final regression PASS。
8. Instagram / Threads / X 登入與下載 regression PASS。
9. README / CHANGELOG / SECURITY / THIRD_PARTY_NOTICES / LICENSE 已同步。
10. APK SHA-256 已產生並核對。
11. Repository owner 明確批准建立 tag 與 GitHub Release。
12. 第三方授權義務已確認並持續由 `THIRD_PARTY_NOTICES.md` 記錄。

### Required release sequence

`release-candidate.yml` 使用 `workflow_dispatch`，因此正式 RC 流程以 default branch `main` 為執行入口。為避免「RC workflow 尚未存在於 default branch，卻要求在 merge 前先執行 RC」的循環依賴，v1.0.0 固定採以下順序：

1. Review `release/v1.0.0-prep`。
2. 在 release branch 完成 Manual Debug CI。
3. 將 release preparation PR merge 到 `main`。
4. 確認 `main` 沒有額外未 review 的變更。
5. 從 `main` 手動執行 **Android Release Candidate** workflow。
6. 下載 signed RC APK 與 `SHA256SUMS.txt`，執行 owner-device final regression。
7. 確認 RC 後 `main` 未再變更。
8. 對該相同 commit 建立 `v1.0.0` tag 與 GitHub Release。

本專案的 project-level license 已決定為 **GNU GPL v3.0 (GPL-3.0)**，完整條款位於 repository 根目錄 `LICENSE`。正式 binary release 仍必須遵守所有 bundled third-party components 的授權與 corresponding-source 義務。

## Release signing architecture

正式 APK 必須使用 release signing key 簽署，不使用 Android debug key。

### Gradle signing 配置

在 `app/build.gradle.kts` 中，release build 透過以下環境變數取得 signing 設定：

- `ANDROID_SIGNING_STORE_FILE`：keystore 檔案的本機或 runner 絕對路徑。
- `ANDROID_SIGNING_STORE_PASSWORD`：keystore 密碼。
- `ANDROID_SIGNING_KEY_ALIAS`：key alias（預期為 `social-video-downloader`）。
- `ANDROID_SIGNING_KEY_PASSWORD`：key 密碼。

只有在上述 4 個環境變數**完整且非空白**時，Gradle 才會建立 release `signingConfig` 並套用至 `release` buildType。若未提供環境變數，release build 預設不會帶 signingConfig，以確保一般環境不會誤用不完整的簽章設定。

### 安全原則

- Keystore（`.jks` / `.keystore`）與相關密碼**嚴禁 commit 到 Git repository**。
- `.gitignore` 已加入 `*.jks`、`*.keystore`、`keystore.properties`、`signing.properties`。
- 不得在 GitHub Issue、Pull Request、聊天記錄或公開 log 中貼出 keystore 檔案、Base64 字串或密碼。

---

## Windows Release Key 建立流程

若尚未建立正式 release signing key，請在 Windows 開發機器的 PowerShell 執行以下流程建立。

### 1. 前置需求

- 已安裝 **JDK 17**（或相容的 JDK）。
- `keytool` 工具已加入 PATH（位於 JDK 的 `bin` 目錄下）。

### 2. 執行 keytool 產生 Keystore

在**專案倉庫外部的目錄**（例如個人安全備份資料夾）開啟 PowerShell，執行：

```powershell
keytool -genkeypair `
  -v `
  -keystore SocialVideoDownloader-release.jks `
  -alias social-video-downloader `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

### 3. 欄位與參數說明

- `-keystore SocialVideoDownloader-release.jks`：keystore 檔案名稱。
- `-alias social-video-downloader`：key alias，必須固定為 `social-video-downloader`。
- `-keyalg RSA` / `-keysize 4096`：使用 4096 位元 RSA 金鑰。
- `-validity 10000`：金鑰有效期限約 27 年。
- **Keystore password**：保護 keystore 檔案的密碼，請使用高強度密碼。
- **Key password**：保護私鑰的密碼（可按 Enter 沿用與 keystore 相同的密碼，或另設高強度密碼）。
- **Certificate identity 欄位**（互動式詢問）：
  - `What is your first and last name?`（CN）：姓名或專案名稱（例如 `Charles Woo` 或 `Social Video Downloader`）
  - `What is the name of your organizational unit?`（OU）：組織單位（可填 `Mobile Development` 或按 Enter 略過）
  - `What is the name of your organization?`（O）：組織名稱（例如 `Social Video Downloader Project`）
  - `What is the name of your City or Locality?`（L）：城市
  - `What is the name of your State or Province?`（ST）：州 / 省
  - `What is the two-letter country code for this unit?`（C）：二碼國碼（例如 `TW`）
  - 最後確認 `Is CN=..., OU=..., O=..., L=..., ST=..., C=... correct?` 輸入 `yes`。

### 4. 驗證產生的 Keystore

產生完成後，執行以下指令驗證內容：

```powershell
keytool -list -v -keystore SocialVideoDownloader-release.jks
```

確認輸出包含：
- Alias name：`social-video-downloader`
- Entry type：`PrivateKeyEntry`
- Certificate SHA-256 fingerprint（建議記錄保存）

---

## 重要警告：Key 備份與遺失風險

> [!CAUTION]
> **一旦 v1.0.0 正式使用此 signing key 發佈，後續所有版本（例如 v1.0.1、v1.1.0、v2.0.0 等）皆必須使用完全相同的 signing identity！**
>
> Android 系統會依據 APK 的 signing certificate 驗證升級合法性。若 signing key 遺失：
> - 既有使用者**無法直接覆蓋安裝新版 App**（系統將回傳 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
> - 使用者必須手動解除安裝舊版才能安裝新版，這將導致使用者的本機設定、登入狀態與暫存資料全數遺失。
> - 此操作在 Android 生態圈中是不可逆的破壞性變更。

### 必備備份清單

請將以下 5 項資訊妥善備份：

1. **`SocialVideoDownloader-release.jks`**（二進位 keystore 檔案）
2. **Keystore password**（keystore 密碼）
3. **Key alias**（`social-video-downloader`）
4. **Key password**（key 密碼）
5. **Certificate fingerprint**（SHA-256 指紋，供日後核對身份）

### 備份存放守則

- **建議至少保留兩份離線備份**（例如加密隨身碟、離線外部硬碟、或冷儲存設備）。
- 密碼資料請存放在個人專用**密碼管理器**（如 Bitwarden、1Password 等）。
- **嚴禁**將 `.jks` 檔案或密碼 commit 到任何 Git repository 或上傳至雲端公開空間。

---

## GitHub Secrets 設定流程

Release Candidate workflow 透過 GitHub Repository Secrets 還原 keystore 並簽署 APK。

### 1. GitHub UI 操作路徑

進入 GitHub 專案頁面：
```text
Repository
  → Settings
  → Secrets and variables
  → Actions
  → New repository secret
```

### 2. 需建立的 4 個 Secrets

| Secret 名稱 | 內容說明 | 範例值 |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | `SocialVideoDownloader-release.jks` 檔案的 Base64 編碼字串 | `MIID...==` |
| `ANDROID_KEYSTORE_PASSWORD` | 建立 keystore 時設定的 keystore password | `（您的密碼）` |
| `ANDROID_KEY_ALIAS` | Key alias，固定為 `social-video-downloader` | `social-video-downloader` |
| `ANDROID_KEY_PASSWORD` | 建立 key 時設定的 key password | `（您的密碼）` |

### 3. 將 JKS 轉換為 Base64 字串

**Windows PowerShell（推薦）：**

```powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("SocialVideoDownloader-release.jks")
) | Set-Clipboard
```

此指令會將 Base64 字串直接複製到剪貼簿，可直接至 GitHub Secrets 頁面貼上。

> [!WARNING]
> 切勿將 Base64 字串貼進 GitHub Issue、Pull Request、commit 訊息或公開聊天視窗中。

**Linux / macOS（參考）：**

```bash
base64 -w 0 SocialVideoDownloader-release.jks
```

macOS（BSD base64）：

```bash
base64 < SocialVideoDownloader-release.jks | tr -d '\n'
```

---

## Release Candidate Workflow

Release Candidate workflow 定義於 `.github/workflows/release-candidate.yml`。

### 觸發方式

- **純手動觸發**（`workflow_dispatch`）。
- 不會因 push 或 PR 自動觸發。
- 不會自動建立 Git tag、不會自動建立 GitHub Release、不會 push main、不會自動 merge 或 publish。

### Workflow 執行步驟

1. **Checkout code**：檢出 release preparation 分支原始碼。
2. **Set up JDK 17**：安裝 Eclipse Temurin JDK 17。
3. **Setup Gradle**：配置 Gradle 與快取。
4. **Run Unit Tests**：執行 `./gradlew test`。
5. **Run Android Lint**：執行 `./gradlew lintDebug`。
6. **Validate release signing secrets & decode keystore**：
   - 驗證 4 個 GitHub Secrets 皆存在且非空白。
   - 將 `ANDROID_KEYSTORE_BASE64` 解碼為 `$RUNNER_TEMP/release.keystore`（僅存在於 runner 暫存目錄，不在 workspace 內）。
   - 設定 `ANDROID_SIGNING_STORE_FILE` 指向該暫存檔案。
7. **Build signed release APK**：
   - 帶入 `ANDROID_SIGNING_STORE_PASSWORD`、`ANDROID_SIGNING_KEY_ALIAS`、`ANDROID_SIGNING_KEY_PASSWORD`。
   - 執行 `./gradlew assembleRelease` 產出 signed release APK。
8. **Copy to deterministic filename**：
   - 將 `app/build/outputs/apk/release/app-release.apk` 複製為 `build-artifacts/SocialVideoDownloader-Android-v1.0.0.apk`。
9. **Verify final APK signature**：
   - 使用 Android Build-Tools 的 `apksigner verify --verbose --print-certs` 驗證最終 production APK。
   - 輸出簽章 scheme（v1, v2, v3 等）、Signer DN 以及 Signer Certificate SHA-256 digest。
   - 此憑證資訊為公開資訊，可安全保留在 CI log 中供核對，且不會輸出任何 private key 或密碼。
10. **Generate SHA256SUMS.txt**：
    - 在 `build-artifacts` 目錄下計算 `SocialVideoDownloader-Android-v1.0.0.apk` 的 SHA-256 並寫入 `SHA256SUMS.txt`。
    - 將 checksum 內容印出至 CI log。
11. **Upload Release Candidate Artifact**：
    - 上傳 Artifact，名稱為 `SocialVideoDownloader-Android-v1.0.0-RC-run-${{ github.run_number }}`。
    - 內容包含：
      - `SocialVideoDownloader-Android-v1.0.0.apk`
      - `SHA256SUMS.txt`
    - 保留期限（retention）：7 天。

---

## 本機 Signed Release Build（選用）

一般建議透過 GitHub Actions Release Candidate workflow 產出正式簽章 APK。若需要在本機執行 signed release build，可透過環境變數傳遞簽章參數。

### PowerShell 執行範例

```powershell
# 設定環境變數（請替換為實際路徑與密碼）
$env:ANDROID_SIGNING_STORE_FILE="C:\path\to\SocialVideoDownloader-release.jks"
$env:ANDROID_SIGNING_STORE_PASSWORD="your-keystore-password"
$env:ANDROID_SIGNING_KEY_ALIAS="social-video-downloader"
$env:ANDROID_SIGNING_KEY_PASSWORD="your-key-password"

# 執行 release build
.\gradlew.bat assembleRelease

# 建置完成後，立即清除環境變數
Remove-Item Env:\ANDROID_SIGNING_STORE_FILE
Remove-Item Env:\ANDROID_SIGNING_STORE_PASSWORD
Remove-Item Env:\ANDROID_SIGNING_KEY_ALIAS
Remove-Item Env:\ANDROID_SIGNING_KEY_PASSWORD
```

### 本機建置安全守則

- **切勿將密碼寫入任何被 Git 追蹤的 script 或檔案中**。
- PowerShell session 結束或建置完成後，請立即清除環境變數。
- Keystore 檔案切勿存放在 repository 工作目錄內。
- 產出的 release APK 位於 `app/build/outputs/apk/release/app-release.apk`。可透過 apksigner 驗證：
  ```powershell
  & "$env:ANDROID_HOME\build-tools\<version>\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
  ```


## Owner-device final regression

下載 Release Candidate Artifact 後至少驗證：

- App 可正常安裝與啟動。
- Android 分享選單可啟動 App 並解析 URL。
- 手動貼網址可解析與下載。
- Instagram login / re-login。
- Threads login / re-login。
- X login / re-login。
- 三個平台 authenticated download。
- Debug 預設 OFF。
- Debug 開啟 / App restart 後設定仍保留。
- 未登入平台提醒顯示正確。
- 下載完成檔案存在於 `Downloads/SocialVideoDownloader/`。

## Final publish

Signed RC 與 owner-device final regression 通過，且 repository owner 明確批准後：

1. 確認 `main` HEAD 與已驗證 RC 的 commit SHA 完全一致；若 RC 後 `main` 有任何新 commit，必須重新執行 RC 與 final regression。
2. 建立 annotated tag：`v1.0.0`，tag 必須指向上述已驗證 commit。
3. 以同一 tag / commit 建立 GitHub Release。
4. 上傳：
   - `SocialVideoDownloader-Android-v1.0.0.apk`
   - `SHA256SUMS.txt`
5. Release notes 以 [CHANGELOG.md](../CHANGELOG.md) 的 v1.0.0 內容為基礎。
6. Release 頁面明確指出 source code 對應 `v1.0.0` tag，並連結 `LICENSE` 與 `THIRD_PARTY_NOTICES.md`。
7. Release 發佈後再次下載 APK，核對 SHA-256。

## Post-release

發佈後：

- 更新 `CHANGELOG.md` 的 release date。
- 確認 README Releases 連結正常。
- 確認 GitHub Release assets 可下載。
- 若出現 blocking regression，使用新 patch version（例如 1.0.1），不要覆寫既有 v1.0.0 tag / assets。
