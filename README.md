# MusicStream

自架、零成本的音訊平台：家裡的 Linux 電腦（Go 伺服器，以 Docker 執行）透過 Tailscale 把自己的音樂收藏串流到 Android App；App 也能直接收聽 Podcast 和網路電台。

| 目錄 | 內容 |
|---|---|
| `compose.yaml`、`.env` | Docker Compose 部署；`.env` 放密碼雜湊和 Tailscale 位址（不進版控） |
| `backend/` | Go 伺服器 `musicserver` 與音樂整理工具 `musicorg`（Clean Architecture） |
| `android/` | Android App（Compose + Media3，多模組 Clean Architecture） |
| `music_data/library/` | 整理後的曲庫：`<歌曲｜純音樂>/<華語｜日本｜韓國｜歐美>/<歌手>/<專輯>/<NN - 歌名>.mp3` |
| `music_data/inbox/` | 新歌的來源資料夾：伺服器會自動分類並搬進 `library/` |
| `music_data/_duplicates/`、`_misc/` | 整理時移出的重複檔與非音樂檔（確認後可自行刪除） |
| `docs/SPEC.md` | 規格書：需求、API、資料模型、驗收狀態 |
| `docs/API.md` | API 文件：所有端點、資料結構、錯誤碼，以及和 App 的比對結果 |
| `docs/ARCHITECTURE.md` | 架構統整：分層、流程、關鍵設計、Go 導讀、部署 |

快速開始請見 [docs/ARCHITECTURE.md §6](docs/ARCHITECTURE.md#6-部署與維運)。
