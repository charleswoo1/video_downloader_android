# Contributing

感謝你對 Social Video Downloader for Android 的貢獻。

## 開發流程

1. 先確認現有 Issue / Pull Request 是否已有相同工作。
2. 以最新 `main` 建立 branch。
3. 讓每個 PR 聚焦於單一功能、修正或維護主題。
4. 補上適合的測試。
5. 本機確認 Gradle build、unit tests 與 lint（若已配置）可通過。
6. 建立 Pull Request，說明變更、驗證方式與已知限制。

## Branch naming

建議：

- `feat/<short-name>`
- `fix/<short-name>`
- `docs/<short-name>`
- `chore/<short-name>`

## Commit messages

建議採簡潔的 Conventional Commit 風格：

- `feat: ...`
- `fix: ...`
- `docs: ...`
- `test: ...`
- `chore: ...`
- `refactor: ...`

## Android coding guidelines

- Kotlin 為主要語言。
- UI 使用 Jetpack Compose。
- 不把長時間下載、網路、yt-dlp 或 FFmpeg 工作直接放在 UI thread。
- Composable 不直接承擔下載引擎職責。
- 核心 parsing / mapping / state logic 儘量保持可單元測試。
- 新增 dependency 時，在 PR 說明用途與必要性。

## Security

請勿把以下資料提交到 repository 或 Issue：

- Cookie
- 登入資訊
- API key / Token
- signing keystore / password
- `local.properties`
- 裝置或個人的敏感資料

安全性問題請依 `SECURITY.md` 處理。
