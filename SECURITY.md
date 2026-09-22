# Security Policy

## Supported versions

本專案正在準備第一個正式版本 `v1.0.0`。

在 `v1.0.0` 正式發布前，安全性修正以目前維護中的最新 `main` 與 release candidate 為主。

正式發布後：

| Version | Supported |
| --- | --- |
| 1.0.x | ✅ |
| < 1.0.0 | ❌ |

若後續發布新的 minor / major 穩定版本，支援範圍會再更新。

## Reporting a vulnerability

若發現安全性問題，請避免在公開 Issue 中貼出可直接利用的漏洞細節、Cookie、Token、帳號資料、簽章金鑰或其他 secrets。

若問題可在不揭露敏感資訊的前提下描述，可先建立 Issue 說明受影響功能與重現範圍；涉及 credentials 或可直接利用的細節時，應改用 GitHub 提供的私密安全通報機制（若 repository 已啟用）。

## Sensitive data

本 repository 不應包含：

- `cookies.txt` 或瀏覽器／App Cookie
- 帳號 Token、session、密碼
- API key
- Android signing keystore (`.jks` / `.keystore`)
- signing password / `keystore.properties`
- `local.properties`
- 私人裝置識別資訊
- 其他 credentials

如果這類資訊被意外提交，請將其視為已洩漏：先撤銷／輪替 credential，再處理 Git history。

## Download scope

本專案不以繞過 DRM、付費牆或其他存取控制為目標。平台解析與下載功能應尊重來源內容的存取權限，並對不支援或受限制的內容提供明確錯誤訊息。
