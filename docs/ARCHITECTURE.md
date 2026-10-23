# MusicStream 架構統整

## 1. 系統全景

```
 ┌─────────────────┐                                        ┌──────────────────────────────┐
 │ Android App     │  HTTPS (pinned)                        │ Docker：musicserver (Go)     │
 │  Compose UI     │ ══ Tailscale（WireGuard 加密）═══════► │  SQLite / inotify / ffmpeg   │
 │  Media3 播放    │    <machine>.<tailnet>.ts.net:8443     │  只監聽 100.x.y.z:8443       │
 │  Room/DataStore │    （在家、在外都走這條）              │  music_data/ 掛進容器        │
 └─────────────────┘                                        └──────────────────────────────┘
        App 直連（電腦關機也能用）：
        ├─ Podcast RSS / 音檔 CDN ◄── iTunes Search API 找節目
        └─ 網路電台串流          ◄── Radio Browser API 找電台
```

伺服器不暴露在網際網路上，連內網也連不到：它只監聽這台電腦的 Tailscale 位址，所以只有登入同一個 Tailscale 帳號的裝置能連線。路由器不做 Port Forwarding，也不需要知道公網 IP。手機在家和在外都走 Tailscale；在同一個 Wi-Fi 時，Tailscale 會自動直連，不會繞到外面。

Tailscale 隧道本身有加密，TLS + pin 仍保留，用來驗證伺服器身分。伺服器位址和 pin 在建置 App 時從 `android/server.properties` 寫入，所以 App 打開就直接進登入頁，不需要貼連線碼。

---

## 2. Clean Architecture 原則

兩端都採用同心圓分層，**依賴只能由外往內**：

```
   Frameworks & Drivers   ← SQLite, net/http, inotify          │ Compose, Retrofit, Room, Media3
   Interface Adapters     ← REST handlers, SQL repos            │ ViewModels, Repository impls
   Use Cases              ← scan, auth, library, playlist...    │ *UseCase 類別
   Entities (Domain)      ← Track, Playlist, trackmeta 規則     │ model/*, repository 介面
```

| 規則 | Go 後端怎麼做 | Android 怎麼做 |
|---|---|---|
| Domain 不依賴框架 | 各功能的 `domain/`、`application/` 與 `shared` 不 import `platform`，也不碰資料庫或 HTTP | 各功能的 `domain` package 只放 model、repository 介面和 UseCase；`:core:common` 是純 Kotlin JVM 模組 |
| 介面放在使用的那一側 | Use case 在 `application` 定義 port（`library/application.Prober`、`stream/application.Transcoder`…） | Repository 介面放在 domain，由 data 實作 |
| 外層透過 DI 組裝 | `internal/app`（composition root）手動注入；後端依功能切分（§3） | 每個模組自己的 Hilt module（`LibraryModule`、`PlaybackModule`…） |
| 錯誤在邊界轉換 | `shared.ErrNotFound` 等 sentinel，由 `platform/httpx` 轉成 HTTP 狀態碼 | `AppError` sealed class，由 `toAppError()` 轉換 Retrofit/IO 例外 |

---

## 3. Go 後端

### 3.1 Feature-based 目錄結構

後端先依**功能**切分，每個功能內部再固定分成 `domain / application / infrastructure / presentation` 四層。要改某個功能時，只需要動該功能的資料夾。

```
backend/
├── cmd/
│   ├── musicserver/                 # 伺服器入口：serve | hash-password | pairing-code | version
│   └── musicorg/                    # 音樂整理 CLI（使用 ingest 功能）
└── internal/
    ├── feature/                     # ★ 依功能切分，每個功能底下固定四層
    │   ├── library/                 # 曲庫
    │   │   ├── domain/              ← Track、Album、Artist、查詢條件、重複判斷、metadata 修正規則、TrackRepository 介面
    │   │   ├── application/         ← Catalog（查詢、修正）、Scanner（掃描）、ports.go（MediaSource、Prober、Hasher）
    │   │   ├── infrastructure/      ← SQLite（track_repository + migrations/0001_tracks.sql）、曲庫資料夾（os.Root）、標籤讀取、hash
    │   │   └── presentation/        ← HTTP handler，並匯出共用的 TrackDTO / Presenter
    │   ├── ingest/                  # 收錄／整理（inbox → library）
    │   │   ├── domain/              ← 搬移計畫（Plan、Move）、檔案分類（音樂、圖片、暫存檔）
    │   │   ├── application/         ← Planner、Executor、Ingestor、ports.go
    │   │   ├── infrastructure/      ← 搬檔、undo log
    │   │   └── presentation/        ← POST /library/ingest
    │   ├── stream/                  # 串流、轉檔、封面（沒有自己的實體，所以沒有 domain/）
    │   │   ├── application/         ← Service：開檔、轉檔、找封面；定義 TrackCatalog、MediaStore、Transcoder 等 port
    │   │   ├── infrastructure/      ← ffmpeg
    │   │   └── presentation/
    │   ├── playlist/                # 播放清單
    │   │   ├── domain/              ← Playlist、名稱與數量限制、Repository 介面
    │   │   ├── application/         ← Service；透過 TrackCatalog port 讀取歌曲
    │   │   ├── infrastructure/      ← SQLite + migrations/0001_playlists.sql
    │   │   └── presentation/
    │   ├── auth/                    # 登入、token
    │   │   ├── domain/              ← User、refresh token、Repository 介面
    │   │   ├── application/         ← Service；ports.go（PasswordHasher、AccessTokenIssuer）
    │   │   ├── infrastructure/      ← bcrypt、JWT、SQLite + migrations/0001_users.sql
    │   │   └── presentation/        ← /auth/login、/refresh、/logout（含限流）
    │   ├── system/                  # /healthz、/server/info
    │   │   ├── application/
    │   │   └── presentation/
    │   ├── discover/                # 線上找歌：搜尋 YouTube / SoundCloud，下載到 inbox
    │   │   ├── domain/              ← Source、Candidate、下載工作、允許下載的網域清單
    │   │   ├── application/         ← Service；ports.go（Fetcher、Inbox、ArtistCatalog）
    │   │   ├── infrastructure/      ← yt-dlp（搜尋、下載、轉 mp3）、把檔案放進 inbox
    │   │   └── presentation/        ← /discover/search、/discover/downloads
    │   └── pairing/                 # 連線碼（備用，App 目前用 server.properties）：位址、pin、公鑰；只在 log 輸出
    │       ├── domain/
    │       ├── application/
    │       └── infrastructure/      ← 列出網卡位址（含 tailscale0、wg0）
    ├── shared/                      # 共用 kernel：錯誤類別、分頁、UserID
    │   └── trackmeta/               #   檔名、標籤、資料夾的分類規則（library 和 ingest 共用）
    ├── platform/                    # 與業務無關的技術積木
    │   ├── httpx/                   #   JSON 回應、錯誤對應、middleware、限流
    │   ├── sqlitex/                 #   連線、各功能的 migration runner、settings
    │   ├── audiotag/                #   ID3、亂碼修復、MP3 時長、ASF(WMA)
    │   ├── filehash/                #   SHA-256
    │   ├── fswatch/                 #   inotify + debounce
    │   └── tlsx/                    #   自簽憑證、SPKI pin
    ├── app/                         # Composition root：組裝功能、掛路由、生命週期
    ├── config/
    └── archtest/                    # 架構測試：以 go test 強制檢查依賴規則
```

### 3.2 依賴規則（由 `internal/archtest` 自動檢查）

```
                    ┌────────── app（組裝，可以看到全部）──────────┐
                    ▼                                              ▼
 feature/x/presentation ──┐                         feature/x/infrastructure ──► platform
                          ├──► feature/x/application ──► feature/x/domain ──► shared
                          └───────────────────────────────────▲
                                                              │
                         feature/y/*  ──（只能用 x 的 domain 型別）┘
```

| 規則 | 說明 |
|---|---|
| 功能裡的程式只能放在四層之一 | `feature/<x>/` 底下不能有其他資料夾或直接放 `.go` 檔 |
| 同一功能內，依賴只能往內 | `domain` 不依賴任何層；`application` 只依賴 `domain`；`infrastructure`、`presentation` 依賴 `domain` 和 `application`（兩者互不依賴） |
| `domain`、`application` 不能用 `platform` | 需要技術能力時，在 `application` 定義 port，由 `infrastructure` 實作 |
| `shared` 不依賴任何東西 | kernel 必須保持最小 |
| `platform` 只依賴 `shared` 和其他 `platform` | 技術積木不知道有哪些功能 |
| 功能之間只能透過對方的 `domain` 型別 | 例如 `playlist/application` 可以用 `library/domain.Track`，但不能呼叫 `library/application` |
| 功能之間的依賴方向固定 | 只允許 `playlist → library`、`stream → library`、`ingest → library`；要新增必須明確修改 `featureDeps`。`discover` 需要已知歌手名單時，透過自己的 `ArtistCatalog` port，由 `app` 接上 library 的 repository，所以不算功能依賴 |
| 唯一的跨層例外 | `playlist/presentation → library/presentation`：播放清單要用同一套 TrackDTO 輸出歌曲 |
| 只有 `app` 能 import `config` 和 `app` | 組裝的責任集中在一處 |

這些規則都寫在 `internal/archtest/arch_test.go`，違反時 `make test` 會直接失敗。`TestRulesRejectViolations` 另外驗證規則本身，確保幾個典型的錯誤依賴確實會被擋下。

### 3.3 每個功能的固定格式

新增一個功能時，照這個樣子建立：

| 檔案或套件 | 角色 |
|---|---|
| `feature/<x>/domain/` | 實體、驗證規則、`Repository` 介面 |
| `feature/<x>/application/` | Use case（`Service`），以及它需要的 port（`ports.go`） |
| `feature/<x>/infrastructure/` | port 的實作：SQLite（加上 `migrations/NNNN_*.sql` 和 `Migrate(ctx, db)`）、檔案系統、外部程式 |
| `feature/<x>/presentation/handler.go` | `Handler.Register(mux, authed)` 自己掛載路由 |
| `app/app.go` | 在 `New` 裡組裝，並把 handler 加進 `newRouter` 的清單 |

Migration 依功能記錄在 `schema_migrations(feature, version)`，各功能可以獨立演進。執行順序由 `app.openDatabase` 決定：library → auth → playlist。

### 3.4 請求流程（播放一首歌）

```
ExoPlayer ─GET /api/v1/tracks/42/stream (Range)─►
  httpx.RequestLog → httpx.Recover → httpx.SecurityHeaders → ServeMux
    → httpx.RequireAuth（JWT → ctx 帶 userID）
      → stream/presentation.Handler.streamTrack
        → stream/application.Service.OpenOriginal
          → TrackCatalog.Get       （port，由 library/infrastructure 的 TrackRepository 實作）
          → MediaStore.Open        （port，由 library/infrastructure 的 MediaStore 實作，os.Root）
        ← http.ServeContent → 206 Partial Content
```

掃描流程：啟動時、inotify 事件、每 6 小時觸發 → `library/application.Scanner.FullScan`
1. 比對 size 和 mtime，只處理有變動的檔案
2. 用 worker pool 讀取 tag 和 SHA-256
3. `trackmeta.ResolveInLibrary` 判斷歌手、專輯、歌名
4. hash 相同但路徑不同 → 視為搬移，沿用原 ID
5. 保留使用者手動修正過的 metadata
6. 刪除檔案已消失的歌曲

線上找歌流程：App 呼叫 `GET /discover/search` → `discover/application.Service` 透過 `Fetcher`（yt-dlp）搜尋，並用 `ArtistCatalog` 提供的曲庫歌手名單猜出歌手和歌名 → 使用者按「＋」→ `POST /discover/downloads` 建立下載工作（排隊 → 下載中 → 完成／失敗）
1. 只接受 domain 允許清單裡的網域（YouTube、SoundCloud），避免伺服器去抓任意或內網網址
2. yt-dlp 下載並用 ffmpeg 轉成 mp3，暫存在 `dataDir/discover`
3. 依歌手和歌名命名後，透過 `Inbox` 放進 inbox
4. 之後交給 ingest：自動分類並搬進 library，掃描後就會出現在音樂庫
5. App 用 `GET /discover/downloads` 查看下載進度

### 3.5 給第一次寫 Go 的導讀

| Go 慣例 | 在本專案的位置 | 說明 |
|---|---|---|
| **一個資料夾就是一個 package** | `feature/library/domain/` 與 `feature/library/infrastructure/` | package 邊界就是 import 邊界，所以「domain 不能 import infrastructure」可以由編譯器和 archtest 保證 |
| **小介面、由使用端定義** | `feature/library/application/ports.go`、`ingest/application/ports.go` | 使用的一方宣告自己需要什麼；實作端不必宣告「我實作了誰」，型別有對應方法就算符合 |
| **錯誤是值** | `shared/errors.go`、`platform/httpx/respond.go` | 用 `fmt.Errorf("…: %w", shared.ErrNotFound)` 包裝錯誤，HTTP 層再用 `errors.Is` 對應成狀態碼 |
| **context.Context** | 每個 repository 和 use case 的第一個參數 | 傳遞取消與逾時；客戶端斷線時，查詢和轉檔會跟著停止 |
| **goroutine 與 channel** | `feature/library/application/scanner.go`（`probeAll`） | 有上限的 worker pool 加上 `sync.WaitGroup` |
| **表格驅動測試** | `shared/trackmeta/filename_test.go` | 一個 slice 列出所有案例，用 `t.Run` 逐一執行 |
| **零依賴的 HTTP** | `feature/*/presentation/handler.go` 的 `Register` | Go 1.22 起的 `ServeMux` 支援 `"GET /tracks/{id}"` 寫法 |
| **embed** | `feature/*/infrastructure/*_repository.go` | `//go:embed migrations/*.sql`，SQL 會跟著功能一起編進執行檔 |

常用指令（Go 安裝在 `~/sdk/go`）：

```bash
cd backend
make test            # go test ./...（含架構測試）
make vet             # go vet + gofmt 檢查
make build           # bin/musicserver、bin/musicorg
make hash-password   # 產生 auth.passwordHash
make run             # 以 config.yaml 啟動
```

### 3.6 依對話需求檢查後補上的功能

這些功能原始對話沒有明講，但要讓這個架構在真實環境安全地跑起來，必須補上：

| # | 補上的功能 | 為什麼需要 |
|---|---|---|
| 1 | **TLS + 公鑰 pin**（自簽，零成本） | VPN 只保護隧道；TLS 讓 App 確認對方真的是這台伺服器，也讓同一個內網裡的其他裝置看不到帳密、token 和音樂 |
| 2 | **帳號登入、JWT、refresh 輪替、限流** | 沒有驗證的話，任何人掃到這個 port 就能下載整個收藏 |
| 3 | **多位址探測** | 在家連內網位址，在外透過 VPN 連 VPN 位址；平行探測，取第一個能連上的 |
| 4 | ~~Firebase 心跳與 CGNAT 警告~~（2026-10-11 移除） | 改用 VPN 後不需要公網 IP，也不受 CGNAT 影響 |
| 5 | **ID3 亂碼修復**（Big5/GBK/SJIS） | 曲庫實測約 25% 的標籤是亂碼或空白 |
| 6 | **YouTube 檔名清洗、頻道名稱過濾** | 對話提到「檔名格式通常很亂」；實測的歌手欄位常是 `xxx Music Channel` |
| 7 | **WMA 支援**（ASF 解析、ffmpeg 轉檔） | 曲庫有 73 首 WMA，而 Android 無法解碼 |
| 8 | **搬檔時保留 ID** | 整理資料夾後，播放清單不會失效 |
| 9 | **metadata 手動修正 API** | 對話建議「先自動猜，之後慢慢整理」，所以需要能修正 |
| 10 | **重複偵測分兩種**（完全相同 / 同一首歌） | 對話提到持續收集後會有 MV 版、歌詞版等重複 |
| 11 | **undo log** | 搬動 8 GB 檔案必須能還原 |
| 12 | **graceful shutdown、Docker 健康檢查與自動重啟** | 背景常駐服務的基本要求 |

---

## 4. Android App

### 4.1 模組

和後端一樣**依功能切模組**：每個 `:feature:*` 模組內部再分 `data / domain / presentation / di` 四個 package；`:core:*` 放跨功能共用的技術積木。

```
                         :app（Navigation、Application、Coil、DI 進入點）
                           │
   ┌─────────┬─────────┬───┴─────┬──────────┬─────────┬──────────┐
   ▼         ▼         ▼         ▼          ▼         ▼          ▼
:feature: :feature: :feature: :feature:  :feature: :feature:  :feature:
 session   library ◄─playlist  podcast    radio     player    discover
   │         │         │         │          │         │          │
   └─────────┴────┬────┴─────────┴──────────┴─────────┴──────────┘
                  ▼
   :core:network  :core:playback  :core:database  :core:ui  :core:common
```

功能之間唯一的依賴是 `:feature:playlist → :feature:library`（播放清單沿用 `TrackDto` 和歌曲列元件）。

| 模組 | 類型 | 責任 | 主要技術 |
|---|---|---|---|
| `:core:common` | Kotlin JVM | `AppError`、`Page<T>`、`runCatchingApp`（沒有 Android 依賴） | Coroutines |
| `:core:network` | Android library | OkHttp 用戶端、TLS pin、虛擬主機與 token 攔截器、`TokenStore`（Keystore 加密）、`RetrofitFactory` | Retrofit 3、OkHttp 5、kotlinx.serialization、DataStore、Hilt |
| `:core:database` | Android library | Room 資料庫（Podcast、集數、電台收藏） | Room 2.8 |
| `:core:ui` | Android library | 主題、共用元件、`UiState`、錯誤訊息、`OffsetPagingSource` | Compose、Material 3、Paging 3 |
| `:core:playback` | Android library | `PlaybackService`（MediaSessionService）、`MediaPlayerController`；對外只公開 `api/`（`PlayerController`、`PlaybackEventListener`） | Media3 1.11（ExoPlayer、Session、OkHttp datasource、HLS） |
| `:feature:session` | Android library | 內建伺服器位址（`server.properties`）、備用的配對流程、位址解析、登入、設定頁 | |
| `:feature:library` | Android library | 音樂庫、歌手、專輯、搜尋、歌曲選單 | |
| `:feature:playlist` | Android library | 播放清單、「我的」頁、加入播放清單 | |
| `:feature:podcast` | Android library | iTunes 搜尋、RSS 解析、訂閱、續播、背景更新 | WorkManager |
| `:feature:radio` | Android library | Radio Browser 熱門與搜尋、收藏、回報點擊 | |
| `:feature:player` | Android library | 迷你播放器、全螢幕播放頁、播放錯誤提示 | |
| `:feature:discover` | Android library | 線上找歌：搜尋 YouTube / SoundCloud、請伺服器下載、下載紀錄 | |
| `:app` | Application | Navigation、`MusicStreamApp`（Hilt、WorkManager、Coil 設定） | Compose BOM 2026.09、Navigation（type-safe）、Coil 3 |

**播放事件的擴充點**：`:core:playback` 不認識 Podcast 和電台。功能模組用 Hilt `@IntoSet` 提供 `PlaybackEventListener`（`EpisodeProgressRecorder`、`RadioPlayReporter`），由 `PlaybackEventDispatcher` 在換曲、暫停和每 10 秒時呼叫。

### 4.2 Presentation：單向資料流

```
Composable ──事件──► ViewModel ──呼叫──► UseCase ──► Repository(介面)
    ▲                    │
    └── StateFlow<UiState>┘    (Loading / Success / Error(AppError))
```

- 每個畫面一個 `@HiltViewModel`，以 `StateFlow` 暴露狀態，畫面端用 `collectAsStateWithLifecycle` 收集
- 分頁：domain 層只回傳 `Page<T>`，由 `OffsetPagingSource` 在 presentation 層轉成 Paging 3（domain 不依賴 AndroidX）
- 跨畫面共用的動作（加入佇列）集中在 `TrackActionsViewModel`；「加入播放清單」由 playlist 模組透過 `LocalTrackMenuExtensions` 加進同一個選單

### 4.3 關鍵設計

#### (1) 虛擬主機 `https://server.musicstream/`

所有指向家用伺服器的 URL（API、串流、封面）都使用這個固定的虛擬主機名稱。

```
請求 → AuthInterceptor（看到虛擬主機 → 加 Bearer）
     → ServerUrlInterceptor（改寫成目前可用的位址，例如 https://<machine>.<tailnet>.ts.net:8443）
         └─ IOException → 強制重新解析 → 若位址不同就重試一次
     → TokenAuthenticator（401 → 同步化 refresh → 重試；refresh 也 401 → 清除 token → 回登入頁）
```

好處：

- 網路從 Wi-Fi 切換到 4G 時，播放器手上的 URI 不需要改變
- ExoPlayer 和 Coil 的快取 key 保持穩定

> 攔截器的順序很重要，auth 必須在改寫之前執行。這個 bug 已由 `NetworkSecurityTest` 抓到並修正。

#### (2) 單一 TrustManager：系統 CA **或** 伺服器的 pin

`ServerTrustManager` 的判斷規則：

- 憑證公鑰符合 pin → 信任（家用伺服器的自簽憑證沒有對應的主機名稱，所以不檢查主機名稱）
- 否則 → 交由系統 CA 驗證（Podcast CDN、Radio Browser）

因此一個 OkHttpClient 就能同時給 Retrofit、ExoPlayer 和 Coil 使用。

#### (3) 位址解析（`ConnectionRepositoryImpl`）

```
候選位址 = server.properties 的 server.hosts（目前只有 Tailscale MagicDNS 名稱）；走配對流程時則是連線碼裡的位址
平行探測 GET /healthz（連線逾時 4 秒）→ 取最先回應的 → 取消其他探測 → 快取
NetworkMonitor：預設網路變動（onAvailable / onLost）→ 清除快取
```

#### (4) 播放（Media3）

- `PlaybackService` 持有 ExoPlayer 和 MediaSession，提供通知列、鎖定畫面、藍牙與 Android Auto 控制
- 設定項：音訊焦點、耳機拔除時暫停、`WAKE_MODE_NETWORK`，以及加大的緩衝（30～120 秒）以應付行動網路
- `MediaPlayerController` 透過 `MediaController` 連到 service，並把狀態轉成 domain 的 `PlayerState`；連線建立前送出的指令會先排隊
- `PlaybackEventDispatcher` 在播放時每 10 秒、暫停和切換集數時發出 checkpoint；`:feature:podcast` 的 `EpisodeProgressRecorder` 收到後儲存 Podcast 進度
- 播放錯誤分類為 `SourceUnavailable`（電台失效）、`Unauthorized` 或其他，並以 Snackbar 提示

#### (5) 安全保存

- Token 以 Android Keystore 中不可匯出的 AES-256-GCM 金鑰加密後，存入 DataStore
- 設定 `allowBackup=false`
- Network security config 只信任系統 CA；允許明文流量，是因為許多電台只有 http 串流

### 4.4 主要畫面

| 分頁 | 畫面 |
|---|---|
| 音樂庫 | 歌曲 / 歌手 / 專輯（篩選、排序、分頁）→ 歌手頁 → 專輯頁 |
| 搜尋 | 線上找歌（`:feature:discover`）：選 YouTube 或 SoundCloud 搜尋，按「＋」由伺服器下載並自動分類到音樂庫；下方顯示下載紀錄 |
| Podcast | 訂閱列表、iTunes 搜尋、RSS 網址訂閱 → 節目頁（下拉更新、進度條、已聽完標記） |
| 電台 | 收藏、熱門（台灣 / 全球）、搜尋 |
| 我的 | 播放清單（新增、改名、刪除、移除歌曲）、設定（伺服器狀態、登出） |
| 共用 | 迷你播放器、全螢幕播放頁（拖動進度、隨機、重複、佇列）、配對頁、登入頁 |

---

## 5. 測試策略與現況

| 層 | 測試 | 數量 |
|---|---|---|
| Go `shared/trackmeta` | 檔名解析表格測試（20 個以上真實檔名）、資料夾提示、曲庫路徑解析 | 4 個函式 |
| Go `platform/audiotag` | 亂碼修復（Big5 / GBK / 雙重編碼 / 正常 Latin-1） | 1 |
| Go `feature/*/domain`、`application` | Track 規則；Auth（登入、輪替、重用偵測、竄改、改密碼）；Scanner（增量、搬移、人工修正保留、刪除）；Ingest（沿用大小寫不同的資料夾、已知歌手、重複內容、暫存檔與寫入中的檔案）；播放清單附加的總數上限 | 9 |
| Go `feature/*/infrastructure` | 曲庫查詢、播放清單（含 cascade） | 2 |
| Go `app`（整合） | 用 `app.New` 組出完整伺服器：未授權、Range 206、錯誤格式 | 3 |
| Go `archtest` | 四層結構、依賴規則、規則本身會擋下典型違規 | 3 |
| Kotlin `:feature:session`、`:feature:podcast` | 連線碼解析；Podcast 續播與完成門檻；RSS（含 XXE 防護、寬鬆日期） | 11 |
| Kotlin `:core:network` | TLS pin（對 / 錯）、虛擬主機改寫與 token refresh、refresh 被撤銷時登出 | 4 |

另外用 curl 對真實曲庫（1,104 首）做過端到端驗證。**App 的 UI 尚未在實機上跑過**，因為這台電腦沒有 KVM，也沒有連接裝置。

---

## 6. 部署與維運

### 6.1 伺服器（Docker Compose）

| 檔案 | 用途 | 進版控 |
|---|---|---|
| `compose.yaml`（專案根目錄） | 一個 `musicserver` 服務：主機網路、uid 1000、健康檢查、`restart: unless-stopped` | 是 |
| `backend/Dockerfile` | 兩階段建置：Go 編譯 → Alpine 執行，內含 ffmpeg；build 參數 `ALPINE_MIRROR` 可以換成較快的套件鏡像 | 是 |
| `backend/deploy/docker/config.yaml` | 容器內的設定檔（路徑都是容器路徑），不含密碼 | 是 |
| `.env`（專案根目錄） | `MUSICSTREAM_PASSWORD_HASH`（bcrypt，用單引號包住）、`MUSICSTREAM_LISTEN`（Tailscale 位址:port） | **否** |

| 主機路徑 | 容器路徑 | 內容 |
|---|---|---|
| `music_data/` | `/music` | 曲庫、inbox、`_duplicates`、`_misc`、undo log |
| `backend/data/` | `/data` | SQLite、`tls/`（伺服器私鑰，決定 pin）、JWT secret |

```bash
cd ~/Documents/app_music_stream
docker compose up -d --build     # 建置並啟動（Docker 開機會自動啟動）
docker compose logs -f           # 看 log
docker compose down              # 停止
```

使用主機網路的原因：伺服器要綁定主機的 Tailscale 位址，容器的橋接網路看不到 `tailscale0`。

**換密碼**：執行 `backend/bin/musicserver hash-password`（或 `docker compose run --rm musicserver hash-password`），把輸出用單引號包起來，寫進 `.env` 的 `MUSICSTREAM_PASSWORD_HASH`，再執行 `docker compose up -d`。換密碼會撤銷所有 refresh token，手機要重新登入。

**不要刪掉 `backend/data/tls/`**：刪掉後伺服器會產生新的金鑰，pin 跟著改變。這時要把新的 pin 更新到 `android/server.properties`，再重新建置 App。

`deploy/musicstream.service`（systemd user service，直接跑主機上的執行檔）仍保留，作為不用 Docker 時的替代方式；兩者不要同時執行。

### 6.2 Tailscale

1. 電腦和手機都安裝 Tailscale，登入同一個帳號，並在 Tailscale 管理頁開啟 MagicDNS
2. 用 `tailscale ip -4` 查電腦的位址，寫進 `.env` 的 `MUSICSTREAM_LISTEN`（例如 `100.x.y.z:8443`）
3. 用 `tailscale status --json` 的 `Self.DNSName` 查 MagicDNS 名稱，連同 port 和 pin 寫進 `android/server.properties`（範本：`server.properties.example`），再建置 App
4. 路由器**不要**設定 Port Forwarding（如果之前設過 8443，請刪掉）
5. 使用時，先在手機打開 Tailscale，再開 App

開機時如果 Tailscale 比 Docker 晚就緒，伺服器會綁不到位址而結束，Docker 會自動重試，直到 Tailscale 就緒。

### 6.3 新增歌曲的流程

```
music_data/inbox/  ──(inotify，檔案 10 秒內沒有再變動)──► Ingestor
   ├─ 音樂      → 依分類規則放到 library/<歌手>/<專輯>/（不分大小寫沿用已有資料夾）
   ├─ 重複內容  → _duplicates/
   ├─ 專輯圖    → 跟著專輯放到 Artwork/
   ├─ 其他檔案  → _misc/
   └─ .part 等下載中的檔案 → 不動，等下載完成
   → 寫 undo log → Scanner 重新掃描 → App 看得到
```

- 平常只要把檔案丟進 `music_data/inbox/` 就好，伺服器會自動處理
- 想先預覽分類結果：停掉伺服器（`docker compose down`），執行 `make organize` 看 TSV 報告；確認後執行 `make organize-apply`
- 也可以直接放進 `library/<歌手>/`，這時以資料夾和檔名為準，只會重新掃描，不會再搬移

### 6.4 Android

用 Android Studio（Panda 2）開啟 `android/`，或用指令列：

```bash
cd android
JAVA_HOME=~/Documents/android-studio-panda2-linux/android-studio/jbr ./gradlew :app:assembleDebug
# APK：app/build/outputs/apk/debug/app-debug.apk
```

Gradle wrapper 從華為雲鏡像下載，並以官方 SHA-256 驗證完整性；台灣連 services.gradle.org 只有約 50 KB/s。

### 6.5 備份

收藏是長時間累積的，建議定期執行：

```bash
rsync -a --delete ~/Documents/app_music_stream/music_data/library/ /media/<外接硬碟>/music_backup/
```

---

## 7. 技術選型

| 領域 | 選擇 | 理由 |
|---|---|---|
| 後端語言 | Go 1.27 | 單一執行檔、低資源、標準函式庫的 HTTP 已足夠 |
| DB | SQLite（modernc，純 Go，不需 cgo） | 零維運；1 萬首規模綽綽有餘；交叉編譯容易 |
| 檔案監控 | fsnotify（inotify） | 對話指定的自動加入機制 |
| 身分驗證 | bcrypt + JWT HS256 + 不透明的 refresh token | 單一使用者情境下簡單又安全 |
| Android UI | Jetpack Compose + Material 3 | 現代標準，支援動態配色 |
| DI | Hilt | 官方推薦，整合 ViewModel、WorkManager 和 Service |
| 網路 | Retrofit 3 + OkHttp 5 + kotlinx.serialization | 攔截器機制可實作虛擬主機和 pin |
| 播放 | Media3 ExoPlayer + MediaSessionService | 背景播放、通知和藍牙的官方方案；支援 HLS 與 Range |
| 本地資料 | Room + DataStore | Podcast 和電台資料不依賴伺服器 |
| 圖片 | Coil 3 | 能共用 OkHttp client，自動帶 token |
| 建置 | AGP 9.1（內建 Kotlin）、Kotlin 2.4.20、Gradle 9.3.1、compileSdk 37 | 與 Android Studio Panda 2 相容 |
