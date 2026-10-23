# MusicStream 規格書

| 項目 | 內容 |
|---|---|
| 版本 | 1.0（2026-10-06） |
| 系統 | 自架音樂串流平台：家用 Linux 伺服器（Go）＋ Android App（Kotlin） |
| 需求來源 | claude.ai 對話「Android music streaming app」 |
| 文件範圍 | 功能需求、非功能需求、API、資料模型、部署前提、驗收狀態 |

---

## 1. 背景與目標

使用者在家中 Linux 電腦上收藏了約 1,600 首歌，而且還在持續增加。這些歌大多是從 YouTube 轉檔來的，檔名雜亂。目標是**零成本**：在外面用 Android 手機串流聽自己的收藏，同時也能聽 Podcast 和網路電台。

| 目標 | 說明 |
|---|---|
| G1 隨時聽自己的音樂 | 在家用 Wi-Fi，在外用行動網路，都能串流家裡電腦上的歌 |
| G2 收藏可以持續成長 | 檔案丟進資料夾就會自動出現在 App，不需要手動維護 |
| G3 零成本 | 不使用付費雲端；在外收聽透過免費 VPN（Tailscale 個人方案或自架 WireGuard） |
| G4 一個 App 聽所有音訊 | 音樂＋Podcast＋網路電台 |
| G5 安全 | 伺服器不暴露在網際網路上，只能從內網或 VPN 連線；連線仍以 TLS 加密並驗證伺服器身分 |

### 1.1 範圍外

- **YouTube 串流／直播**：有授權與服務條款問題（對話中已排除）
- **平台獨家 Podcast**：Spotify、SoundOn、KKBOX 的獨家內容沒有公開 RSS，無法取得
- **多使用者社群功能**：資料庫已預留 `users` 表，但目前只支援單一帳號

---

## 2. 部署前提（對話結論）

| 項目 | 狀態 | 備註 |
|---|---|---|
| 對外連線 | Tailscale，**不做** Port Forwarding | 2026-10-11 起取代「Port Forwarding + Firebase 存 IP」；公網 IP 變動或 CGNAT 都不影響 |
| 伺服器位址 | 只監聽這台電腦的 Tailscale 位址 `100.x.y.z:8443`（MagicDNS：`<machine>.<tailnet>.ts.net`） | 內網 `192.168.x.x` 連不到伺服器；在家也走 Tailscale（同一個 Wi-Fi 時會自動直連） |
| 部署方式 | Docker Compose（`compose.yaml`），映像檔內含 ffmpeg | 取代 systemd user service；見 ARCHITECTURE §6.1 |
| 規模 | 目前 1,104 首（整理後），目標 1 萬首以上 | SQLite＋增量掃描，足以應付這個量 |

---

## 3. 使用情境

| # | 角色 | 情境 |
|---|---|---|
| U1 | 使用者 | 在電腦用 `docker compose up -d` 啟動伺服器；手機開啟 Tailscale，打開 App 直接輸入帳號密碼登入（伺服器位址在建置 App 時就已寫入） |
| U2 | 使用者 | 通勤時用 4G 加 Tailscale 播放家裡的歌；回家連上 Wi-Fi 後不需要任何操作 |
| U3 | 使用者 | 用歌名、歌手或專輯（中、英、日文）搜尋，並瀏覽歌手和專輯 |
| U4 | 使用者 | 把新下載的 mp3 丟進資料夾，幾秒後就出現在 App |
| U5 | 使用者 | 修正自動猜錯的歌手或歌名，之後重新掃描也不會被覆蓋 |
| U6 | 使用者 | 收藏歌曲、建立播放清單（多台裝置同步，資料存在伺服器） |
| U7 | 使用者 | 訂閱 Podcast，聽到一半關掉，下次從同一個位置接著聽；電腦關機也能聽 |
| U8 | 使用者 | 搜尋台灣電台並收藏；電台串流失效時看到明確提示 |
| U9 | 使用者 | 螢幕關閉、切到其他 App，或從耳機和鎖定畫面控制時，播放都不中斷 |
| U10 | 管理者 | 查看重複歌曲清單（同一首歌的 MV 版、歌詞版） |

---

## 4. 功能需求

### 4.1 音樂資料夾整理（`musicorg` CLI）

| ID | 需求 | 實作 |
|---|---|---|
| FR-ORG-1 | 依標籤／檔名／資料夾推斷「歌手／專輯／歌名」，搬到 `library/<類型>/<語區>/<歌手>/<專輯>/<NN - 歌名>.ext` | `trackmeta.Resolve`、`ingest.Planner` |
| FR-ORG-1a | 第一層「類型」：`歌曲`（有人聲）或 `純音樂`（配樂、鋼琴、off vocal、INST、伴奏…）；第二層「語區」：`華語`、`日本`、`韓國`、`歐美`、`其他` | `trackmeta.Classify`：曲庫已有的歌手沿用原本的語區與類型；標題／專輯／曲風含 piano、off vocal、(INST)、鋼琴等關鍵字就歸純音樂；新歌手依文字判斷（假名→日本、諺文→韓國、漢字→華語、拉丁字母→歐美） |
| FR-ORG-2 | 修復舊 ID3 亂碼（Big5、GBK、Shift-JIS 被誤存成 Latin-1；UTF-8 被誤讀成 Shift-JIS） | `audiotag.RepairText`：以檔名比對加上常用字區間評分，選出最可能的編碼 |
| FR-ORG-3 | 清除 YouTube 雜訊，例如【Official MV】、(歌詞版)、頻道名稱當成歌手 | `trackmeta.ParseFilename`、`sanitizeTags` |
| FR-ORG-4 | 完全相同的檔案（SHA-256）移到 `_duplicates/`，非音樂檔移到 `_misc/`，專輯掃描圖移到 `Artwork/` | `ingest.Planner`（`splitDuplicates`、`planOther`） |
| FR-ORG-5 | 預設只試跑（dry-run），先產出 TSV 報告；正式執行時寫入可還原的 undo log | `-apply`、`-undo` |

**本次執行結果（2026-10-06）**：搬移 1,530 個檔案，0 個失敗。

| 輸出 | 數量 | 大小 |
|---|---|---|
| `library/` 音樂（365 位歌手） | 1,104 首 | 7.6 GB |
| `_duplicates/` 完全相同的重複檔 | 174 個 | 802 MB |
| `_misc/`（Audacity、wmv 影片等） | 221 個 | 67 MB |
| `Artwork/` 專輯掃描圖 | 31 張 | — |

還原指令：`bin/musicorg -undo music_data/.musicorg/undo-20261006-230412.jsonl`

### 4.1.1 自動收錄（inbox → library）

| ID | 需求 | 實作 |
|---|---|---|
| FR-ING-1 | 新音樂丟進來源資料夾 `music_data/inbox/`（可含子資料夾），伺服器會自動依 §4.1 規則分類並搬進 `library/` | `ingest.Ingestor`；觸發時機：啟動、inbox 的 inotify 事件、每 6 小時，或 `POST /library/ingest` |
| FR-ING-2 | 已存在的歌手、專輯資料夾不分大小寫沿用，不會再多開一個（例如 `clannad - x.mp3` 會放進 `CLANNAD/`） | `FileSystem.ExistingChild` |
| FR-ING-2a | 曲庫已有的歌手放回原本的「類型／語區」（例如新的 Yiruma 會進 `純音樂/韓國/Yiruma/`） | `PlanOptions.KnownCategories` |
| FR-ING-3 | 解析檔名時參考曲庫中已有的歌手（例如「征服 - 那英」能判斷出那英是歌手） | `PlanOptions.KnownArtists` |
| FR-ING-4 | 內容和曲庫中某首歌完全相同（SHA-256）時，移到 `_duplicates/` | `PlanOptions.KnownContent` |
| FR-ING-5 | 不碰還在寫入的檔案：修改時間未滿 `ingestMinAge`（預設 10 秒）的檔案，以及 `.part`、`.crdownload`、`.tmp` 等下載暫存檔 | `PlanOptions.MinAge`、`tempExt` |
| FR-ING-6 | 每次搬移都寫入 undo log：`music_data/.musicorg/ingest-<時間>.jsonl` | 可用 `musicorg -undo` 還原 |
| FR-ING-7 | 搬完後自動重新掃描，幾秒內就會出現在 App | `app.App.ingest` → `library.Scanner.FullScan` |

### 4.2 伺服器：曲庫

| ID | 需求 | 實作 |
|---|---|---|
| FR-LIB-1 | 啟動時完整掃描；之後只處理 size 或 mtime 有變的檔案（增量） | `library.Scanner`：1,104 首首次掃描約 2 秒，含 SHA-256 |
| FR-LIB-2 | 新檔案出現時，在 5 秒內自動加入曲庫（inotify 加 debounce） | `fswatch.Watcher` |
| FR-LIB-3 | 每 6 小時重新掃描一次作為保險 | `config.library.rescanInterval` |
| FR-LIB-4 | 檔案搬移或改名時保留原 ID，播放清單不會壞掉 | 以內容 hash 重新對應 |
| FR-LIB-5 | 歌手和專輯不分大小寫合併（例如 CLANNAD 和 clannad） | `domain.NewArtistID` |
| FR-LIB-6 | 支援伺服器端搜尋、分頁、排序（歌名、歌手、專輯、最近加入） | `GET /tracks?q=&sort=&limit=&offset=` |
| FR-LIB-7 | 手動修正 metadata，修正過的資料不會被掃描覆蓋 | `PATCH /tracks/{id}`、`user_edited` |
| FR-LIB-8 | 找出重複：完全相同的檔案，以及同歌手同歌名的不同上傳版本（Remix、INST、Live 不算重複） | `GET /library/duplicates` |
| FR-LIB-9 | 讀取時長和位元率：MP3 用 Xing/VBRI/CBR，WMA 解析 ASF header | `platform/audiotag`（`mp3duration.go`、`asf.go`） |
| FR-LIB-10 | 封面：優先用內嵌圖，否則用資料夾中的 cover/folder 圖，再不行就用 `Artwork/` 內的第一張 | `stream.TrackArtwork` |

### 4.3 伺服器：串流

| ID | 需求 | 實作 |
|---|---|---|
| FR-STR-1 | 支援 HTTP Range，ExoPlayer 才能任意拖動進度 | `http.ServeContent` |
| FR-STR-2 | 遇到 Android 無法解碼的格式（WMA），若有 ffmpeg 就即時轉成 MP3 | `?transcode=mp3`；沒有 ffmpeg 時，`playable=false` |
| FR-STR-3 | 封面加上 ETag 和 7 天快取 | `stream/presentation` |

### 4.4 伺服器：帳號與安全

| ID | 需求 | 實作 |
|---|---|---|
| FR-AUTH-1 | 帳號密碼登入（bcrypt cost 12）；帳號寫在設定檔，用 `hash-password` 產生密碼雜湊 | `auth.Service.Login` |
| FR-AUTH-2 | Access token 為 JWT HS256，效期 1 小時；secret 第一次啟動時隨機產生並保存 | `auth/infrastructure.NewJWT` |
| FR-AUTH-3 | Refresh token 效期 90 天，只存 SHA-256，每次使用後輪替；舊 token 被重用時撤銷該使用者所有 session | `auth.Service.Refresh` |
| FR-AUTH-4 | 登入和 refresh 限流：每個 IP 每分鐘約 5 次 | `httpx.IPLimiter`（`auth/presentation` 套用） |
| FR-AUTH-5 | 帳號不存在時也做一次 bcrypt 比對，避免從回應時間猜出帳號 | `dummyHash` |
| FR-AUTH-6 | 改密碼時撤銷所有 refresh token | `EnsureUser` |
| FR-SEC-1 | 預設使用自簽 TLS（ECDSA P-256），私鑰只在伺服器（`backend/data/tls/`），App 以建置時寫入的公鑰指紋（pin）驗證伺服器 | `platform/tlsx`（`LoadOrCreateSelfSigned`、`SPKIPin`） |
| FR-SEC-2 | 串流與 API 只允許 ECDHE + AEAD 加密套件：握手用私鑰簽章，音樂資料以每條連線的對稱金鑰（AES-GCM / ChaCha20）加密，具前向保密 | `tlsx.ServerConfig` |
| FR-SEC-3 | 只監聽 Tailscale 位址，內網與本機都連不到；位址寫在不進版控的 `.env`（`MUSICSTREAM_LISTEN`） | `config.Load` 的環境變數覆寫、`compose.yaml` |

### 4.5 伺服器：配對（備用）

目前 App 使用建置時寫入的伺服器位址（FR-APP-1），不需要連線碼。以下功能保留給沒有 `server.properties` 的建置使用。

| ID | 需求 | 實作 |
|---|---|---|
| FR-PAIR-1 | 列出這台電腦的內網與 VPN 位址（192.168.x、10.x、Tailscale 100.x），排除 docker 介面；不使用公網 IP | `pairing/infrastructure.LAN` |
| FR-PAIR-2 | 啟動時印出連線碼 `MS1:…`，內含 hosts、port（取自 `server.listen`）、scheme、pin、公鑰 | `pairing/application.Pairing` |

### 4.6 伺服器：播放清單

| ID | 需求 |
|---|---|
| FR-PL-2 | 播放清單 CRUD；同一首歌可以重複出現；可以整份替換（用於排序或移除），也可以附加歌曲 |
| FR-PL-3 | 只能存取自己的播放清單；歌曲被刪除時，連帶從清單移除（FK cascade） |

### 4.7 Android App

| ID | 需求 | 實作 |
|---|---|---|
| FR-APP-1 | 伺服器位址：建置時從不進版控的 `android/server.properties`（MagicDNS 名稱、port、pin）寫入，打開 App 直接進登入頁。沒有這個檔案時，才走配對流程（貼上連線碼，或手動輸入位址與 pin） | `ConnectionStore.BUNDLED`、`PairScreen`、`ConnectionCode` |
| FR-APP-2 | 自動選擇位址：平行探測所有候選位址的 `/healthz`，取最先回應的（目前只有 Tailscale 一個位址）；切換網路或連上 VPN 後重新探測，連線失敗時自動重試一次 | `ConnectionRepositoryImpl`、`ServerUrlInterceptor`、`NetworkMonitor` |
| FR-APP-3 | 登入，token 以 Android Keystore AES-GCM 加密保存；遇到 401 時自動 refresh，refresh 失敗就回到登入畫面 | `TokenStore`、`TokenAuthenticator` |
| FR-APP-4 | 音樂庫：歌曲、歌手、專輯三個分頁（Paging 3），支援篩選和排序 | `LibraryScreen` |
| FR-APP-5 | 歌手頁、專輯頁，提供「全部播放」和「隨機播放」 | `ArtistScreen`、`AlbumScreen` |
| FR-APP-6 | 全域搜尋：歌手、專輯、歌曲 | `SearchScreen` |
| FR-APP-7 | 歌曲選單：加入播放清單（可當場新建）、加到播放佇列（「收藏的歌曲」已移除，改用播放清單） | `TrackActionsViewModel` |
| FR-APP-8 | 播放器：背景播放、通知列、鎖定畫面、藍牙、耳機拔除時暫停、音訊焦點；迷你播放器加全螢幕播放頁；隨機和重複播放；播放佇列 | `PlaybackService`（Media3） |
| FR-APP-9 | Podcast：iTunes 搜尋、用 RSS 網址訂閱、集數列表、續播、標記聽完、每 6 小時在 Wi-Fi 下更新 | `PodcastRepositoryImpl`、`PodcastRefreshWorker` |
| FR-APP-10 | 電台：Radio Browser 熱門和搜尋（台灣或全球）、收藏；播放失敗時以 Snackbar 提示；回報點擊次數 | `RadioRepositoryImpl` |
| FR-APP-11 | 設定頁：伺服器版本、歌曲數、掃描中狀態、是否支援 WMA 轉檔；登出；忘記伺服器 | `SettingsScreen` |
| FR-APP-12 | 無法播放的歌曲顯示為停用，並說明原因（需要 ffmpeg） | `TrackRow` |

---

## 5. 非功能需求

| 類別 | 需求 | 現況 |
|---|---|---|
| 效能 | 1 萬首內，列表和搜尋 < 100 ms | SQLite 加索引，用 LIKE 子字串搜尋（中文 2 字也能找） |
| 效能 | 新增歌曲的增量掃描只讀有變動的檔案 | 未變動檔案只比對 size 和 mtime |
| 可用性 | 不同網路之間切換，使用者不需要做任何操作 | 虛擬主機加自動重新解析位址 |
| 可用性 | 電腦關機時 Podcast 和電台照常可用 | 兩者直接連到原始來源 |
| 安全 | 傳輸加密，伺服器身分可驗證 | TLS 加 SPKI pin，不依賴 CA 或網域 |
| 安全 | 密碼與 token 不以明文保存 | 伺服器存 bcrypt 和 SHA-256；手機用 Keystore AES-GCM，`allowBackup=false` |
| 安全 | 處理不可信的輸入 | RSS 關閉 XXE；JSON body 限制 1 MB 且拒絕未知欄位；路徑限制在 `os.Root` 內 |
| 維運 | 背景常駐，當機自動重啟 | Docker Compose（`restart: unless-stopped` 加健康檢查）；Docker 開機自動啟動 |
| 維運 | 結構化日誌，每個請求有 request id | `log/slog`，可切換 text 或 json |
| 成本 | 0 元 | 不使用雲端服務；VPN 用 Tailscale 個人方案或自架 WireGuard |
| 相容性 | Android 8.0（API 26）以上 | minSdk 26、targetSdk 36 |

---

## 6. API 規格（REST，`/api/v1`）

> 本節是摘要。完整的參數、回應格式、錯誤碼，以及和 App 的比對結果，請見 [API.md](API.md)。

- 傳輸使用 HTTPS（自簽憑證加 pin），資料格式為 JSON（camelCase），時間單位為 Unix 毫秒
- 除了 `/healthz` 和 `/auth/*`，都需要帶 `Authorization: Bearer <accessToken>`
- 錯誤格式為 `{"error":{"code":"not_found","message":"..."}}`，code 有：`invalid_argument`、`invalid_json`、`unauthenticated`、`invalid_token`、`not_found`、`conflict`、`unsupported`、`rate_limited`、`internal`

| Method | Path | 說明 |
|---|---|---|
| GET | `/healthz` | `{status, version}`（不需驗證，App 探測位址用；路徑在根目錄，不在 `/api/v1` 底下） |
| POST | `/auth/login` | `{username, password, deviceName}` → `TokenPair` |
| POST | `/auth/refresh` | `{refreshToken}` → 新的 `TokenPair`（輪替） |
| POST | `/auth/logout` | `{refreshToken}` → 204 |
| GET | `/server/info` | `{version, trackCount, scanning, lastScanAt, transcodeAvailable}` |
| POST | `/library/scan` | 202，在背景重新掃描（掃描中則回 409） |
| POST | `/library/ingest` | 立即收錄 inbox，回傳 `{audio, duplicates, artwork, misc, failed}` |
| GET | `/library/duplicates` | `{groups:[{reason: identical\|same_song, tracks}]}` |
| GET | `/tracks` | `q, artistId, albumId, sort(title\|artist\|album\|-addedAt), limit≤500, offset` → `Page<Track>` |
| GET | `/tracks/{id}` | 單一歌曲 |
| PATCH | `/tracks/{id}` | `{title?, artist?, album?, albumArtist?, trackNo?, year?, genre?}`，會標記 `userEdited` |
| GET | `/tracks/{id}/stream` | 原始檔，支援 Range（206）；加 `?transcode=mp3` 改為即時轉檔 |
| GET | `/tracks/{id}/artwork` | 封面圖（支援 ETag 和 304） |
| GET | `/artists` | `q, limit, offset` → `Page<Artist>` |
| GET | `/artists/{id}` | `{artist, albums}` |
| GET | `/albums` | `artistId, q, limit, offset` → `Page<Album>` |
| GET | `/albums/{id}` | `{album, tracks}` |
| GET | `/search` | `q, limit`（每類筆數 1～50，不填或超出範圍時用 20）→ `{tracks, artists, albums}` |
| GET / POST | `/playlists` | 列表；新增 `{name, trackIds}`（回 201） |
| GET / PATCH / DELETE | `/playlists/{id}` | 讀取（含歌曲）、改名 `{name}`、刪除 |
| PUT / POST | `/playlists/{id}/tracks` | 整份替換或附加 `{trackIds}` |

**Track 範例**

```json
{
  "id": 1104, "title": "沙耶の眠れるレクイエム", "artist": "風音",
  "album": "沙耶の眠れるレクイエム／Saya's Song", "albumArtist": "",
  "artistId": "4eaa4cdd9ae1a44e", "albumId": "1cd33b82a438459e",
  "trackNo": 5, "year": 0, "genre": "Other", "durationMs": 393288, "bitrateKbps": 320,
  "size": 15754870, "format": "mp3",
  "streamUrl": "/api/v1/tracks/1104/stream", "artworkUrl": "/api/v1/tracks/1104/artwork",
  "hasArtwork": true, "playable": true,
  "metaSource": "tags", "userEdited": false, "addedAt": 1791299740488
}
```

**TokenPair**：`{tokenType:"Bearer", accessToken, expiresIn(秒), refreshToken:"<id>.<secret>", refreshExpiresAt}`

---

## 7. 資料模型

### 7.1 伺服器 SQLite（`data/musicstream.db`，WAL 模式）

| 表 | 主要欄位 | 說明 |
|---|---|---|
| `tracks` | path(UNIQUE)、title、artist、album、album_artist、grouping_artist、artist_id、album_id、track_no、duration_ms、format、content_hash、has_artwork、meta_source、user_edited、search_text | 歌手和專輯從這張表聚合而來，不另外建表 |
| `users` | username(UNIQUE)、password_hash | 啟動時依設定檔建立或更新 |
| `refresh_tokens` | id、user_id、token_hash、expires_at、revoked_at | 只存 hash |
| `playlists` / `playlist_tracks` | (playlist_id, position) → track_id | 保留順序，允許同一首歌重複 |
| `settings` | key / value | jwt_secret、server_id |

Migration 檔以 `go:embed` 嵌入程式，每個功能的版本記錄在 `schema_migrations(feature, version)`，可以各自獨立演進。

### 7.2 App Room（`musicstream.db`）

| 表 | 用途 |
|---|---|
| `podcasts` | 已訂閱的節目（feedUrl PK） |
| `episodes` | 集數、`positionMs`、`completed`；重新整理時保留收聽進度（`merge`） |
| `radio_favorites` | 收藏的電台 |

DataStore `session`：connection（JSON），以及 access 和 refresh token（Keystore 加密）。

### 7.3 連線碼（備用）

目前使用 `android/server.properties`，不需要連線碼；格式保留給配對流程使用。

`MS1:` 後面接 base64url 編碼的 JSON：`{v:1, hosts:[內網與 VPN 位址…], port, scheme, pin?, pk?}`

- `pk`：伺服器公鑰（base64 DER SPKI），App 會檢查 SHA-256(pk) 等於 `pin`
- 舊版連線碼裡的 `sid`、`fb`、`ek`（Firebase 時期）會被忽略，舊碼仍可配對

---

## 8. 驗收狀態

| 項目 | 結果 |
|---|---|
| Go 單元、整合與架構測試 | 9 個套件全數通過，含 inbox 收錄整合測試，以及檢查依賴方向的架構測試 |
| inbox 端到端驗證 | 真實伺服器：把既有歌曲的副本丟進 inbox，約 7 秒後被判定為重複並移到 `_duplicates` |
| 實機 API 驗證 | 以真實曲庫跑 curl：登入、401、錯誤密碼、Range 206、封面 200、播放清單、PATCH、重複偵測、refresh 輪替與重用偵測，全部符合預期 |
| Android 單元測試 | 15 個全數通過（連線碼、用例、RSS 解析與日期、TLS pin、虛擬主機改寫、token refresh） |
| Docker 部署驗證（2026-10-11） | 以 MagicDNS 名稱連線：`/healthz`、登入、伺服器資訊、Range 206、WMA 轉 MP3 都正常；伺服器憑證的 pin 與 `server.properties` 相符；從內網與本機連不到 |
| Android 建置 | Debug APK 與 Release APK（R8 縮減，3 MB）建置成功，編譯警告 0 個 |
| **尚未驗證** | **App 尚未在實機上執行**，也還沒從手機的 4G 透過 Tailscale 連線過。UI 與播放流程需要在手機上跑一次 |

測試期間發現並修正的 bug：OkHttp 攔截器的順序錯誤，會讓所有 API 都缺少 token。這個問題由 `NetworkSecurityTest` 抓到。

---

## 9. 已知限制與後續工作

| # | 項目 | 建議 |
|---|---|---|
| L1 | 73 首 WMA 要即時轉檔才能播放，轉檔時不能拖動進度 | Docker 映像檔已內含 ffmpeg，可以播放；想要能拖動進度，可一次性轉成 mp3 |
| L2 | 45 首歌的歌手推斷不出來（歸在 `Unknown Artist`） | 用 `PATCH /tracks/{id}` 修正；之後可以在 App 加編輯畫面 |
| L3 | 華語歌在 MusicBrainz 上資料少 | 之後可以整合 AcoustID 或 MusicBrainz 做自動辨識（選用） |
| L4 | 搜尋使用 LIKE | 超過 10 萬首時，再考慮 FTS5 trigram |
| L5 | 單一使用者 | 資料表已預留 owner；要多人使用時，再加帳號管理 API |
| L6 | 沒有離線下載音樂 | 可以用 Media3 DownloadService 實作 |
| L7 | 備份 | 收藏只存在一顆硬碟上；建議另外 rsync 到外接硬碟 |
