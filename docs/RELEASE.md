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

1. Release branch 與預定 release commit 已完成 review。
2. `./gradlew test` PASS。
3. `./gradlew lintDebug` PASS。
4. Debug CI PASS。
5. Release Candidate signed APK build PASS。
6. Owner-device regression PASS。
7. Instagram / Threads / X 登入與下載 regression PASS。
8. README / CHANGELOG / SECURITY / THIRD_PARTY_NOTICES 已同步。
9. APK SHA-256 已產生並核對。
10. Repository owner 明確批准建立 tag 與 GitHub Release。
11. **Project-level license 已由 repository owner 明確決定，且第三方授權義務已確認。**

第 11 項未完成時，不建立公開 production Release。

## Signing

正式 APK 必須使用 release signing key，不使用 Android debug key。

Release Candidate workflow 預期使用 GitHub Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Signing keystore 與密碼不得 commit 到 repository。

### 建立 keystore（僅在尚未建立時）

範例：

```bash
keytool -genkeypair \
  -v \
  -keystore social-video-downloader-release.jks \
  -alias social-video-downloader \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

請自行安全備份 keystore。若遺失正式 signing key，後續版本更新會受到影響。

### 將 keystore 轉成 GitHub Secret

Linux / macOS：

```bash
base64 -w 0 social-video-downloader-release.jks
```

macOS 若不支援 `-w`：

```bash
base64 < social-video-downloader-release.jks | tr -d '\n'
```

Windows PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("social-video-downloader-release.jks"))
```

將結果設為 `ANDROID_KEYSTORE_BASE64`。

## Release Candidate workflow

`.github/workflows/release-candidate.yml` 僅接受手動執行。

它會：

1. 執行 unit tests。
2. 執行 Android lint。
3. 驗證 signing secrets。
4. 建置 signed release APK。
5. 產生固定檔名 APK。
6. 產生 `SHA256SUMS.txt`。
7. 上傳 Release Candidate Artifact。

此 workflow **不會**：

- 建立 Git tag。
- 建立 GitHub Release。
- 自動發佈 production release。

這保留 owner 最後的人工作業 gate。

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

Owner 明確批准後：

1. 將 release prep PR squash merge 到 `main`。
2. 確認 `main` 指向已驗證的 release commit。
3. 建立 annotated tag：`v1.0.0`。
4. 以同一 commit 建立 GitHub Release。
5. 上傳：
   - `SocialVideoDownloader-Android-v1.0.0.apk`
   - `SHA256SUMS.txt`
6. Release notes 以 [CHANGELOG.md](../CHANGELOG.md) 的 v1.0.0 內容為基礎。
7. Release 發佈後再次下載 APK，核對 SHA-256。

## Post-release

發佈後：

- 更新 `CHANGELOG.md` 的 release date。
- 確認 README Releases 連結正常。
- 確認 GitHub Release assets 可下載。
- 若出現 blocking regression，使用新 patch version（例如 1.0.1），不要覆寫既有 v1.0.0 tag / assets。
