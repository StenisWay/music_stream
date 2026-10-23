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

## 架構設計

兩端都採用 Clean Architecture，並且**先依功能切分、再依層切分**：要改某個功能時，只需要動該功能的資料夾或模組。依賴方向一律由外往內，domain 不認識任何框架。完整說明見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

### Go 後端（`backend/`）

```
backend/
├── cmd/
│   ├── musicserver/          # 伺服器入口：serve | hash-password | pairing-code | version
│   └── musicorg/             # 音樂整理 CLI
└── internal/
    ├── feature/              # 依功能切分，每個功能底下固定四層
    │   ├── library/          #   曲庫：掃描、查詢、分類（類型／地區）、metadata 修正
    │   ├── ingest/           #   收錄：inbox → library 自動分類搬檔，含 undo log
    │   ├── stream/           #   串流（Range）、WMA 即時轉檔、封面
    │   ├── playlist/         #   播放清單
    │   ├── auth/             #   登入、JWT、refresh 輪替、限流
    │   ├── discover/         #   用 yt-dlp 搜尋 YouTube / SoundCloud，下載到 inbox
    │   ├── system/           #   /healthz、/server/info
    │   └── pairing/          #   連線碼（位址、pin、公鑰）
    ├── shared/               # 共用 kernel：錯誤、分頁、ID；trackmeta 分類規則
    ├── platform/             # 技術積木：httpx、sqlitex、audiotag、filehash、fswatch、tlsx
    ├── app/                  # Composition root：手動注入、掛路由、生命週期
    ├── config/
    └── archtest/             # 架構測試：用 go test 檢查依賴規則
```

每個功能內部的四層：

| 層 | 內容 | 可以依賴 |
|---|---|---|
| `domain/` | 實體、驗證規則、`Repository` 介面 | 只有 `shared` |
| `application/` | Use case（`Service`）與它需要的 port（`ports.go`） | `domain` |
| `infrastructure/` | port 的實作：SQLite（含該功能自己的 migration）、檔案系統、ffmpeg、yt-dlp | `domain`、`application`、`platform` |
| `presentation/` | HTTP handler，自己用 `Register` 掛路由 | `domain`、`application`、`platform` |

主要規則：

- **介面放在使用端**：use case 需要的能力（例如 `Transcoder`、`Prober`）在 `application` 宣告成 port，由 `infrastructure` 實作，所以業務邏輯不碰資料庫或 HTTP。
- **功能之間只能用對方的 `domain` 型別**，而且方向固定：`playlist → library`、`stream → library`、`ingest → library`。要新增依賴必須明確修改 `archtest` 的 `featureDeps`。
- **錯誤在邊界轉換**：內部回傳 `shared.ErrNotFound` 等 sentinel error，由 `platform/httpx` 轉成 HTTP 狀態碼。
- **規則由測試強制**：違反依賴規則時 `make test` 會直接失敗。
- **只用標準庫做 HTTP**：Go 1.22 的 `ServeMux`；資料庫是 SQLite，migration 以 `go:embed` 編進執行檔。

### Android App（`android/`）

```
                          :app（Navigation、Application、DI 進入點）
                            │
  ┌─────────┬──────────┬────┴─────┬─────────┬────────┬─────────┐
  ▼         ▼          ▼          ▼         ▼        ▼         ▼
session  library ◄── playlist  podcast   radio    player   discover     ← :feature:*
  │         │          │          │         │        │         │
  └─────────┴──────────┴────┬─────┴─────────┴────────┴─────────┘
                            ▼
      :core:network  :core:playback  :core:database  :core:ui  :core:common
```

| 模組 | 責任 |
|---|---|
| `:core:common` | `AppError`、`Page<T>`（純 Kotlin，沒有 Android 依賴） |
| `:core:network` | OkHttp、TLS pin、虛擬主機與 token 攔截器、Keystore 加密的 `TokenStore` |
| `:core:database` | Room（Podcast、集數、電台收藏） |
| `:core:ui` | 主題、共用元件、`UiState`、Paging 轉接 |
| `:core:playback` | Media3 `PlaybackService`、`PlayerController`；只公開 `api/` |
| `:feature:session` | 伺服器位址、位址解析、登入、設定 |
| `:feature:library` | 音樂庫、歌手、專輯、搜尋 |
| `:feature:playlist` | 播放清單、「我的」頁 |
| `:feature:podcast` | iTunes 搜尋、RSS 訂閱、續播、背景更新 |
| `:feature:radio` | Radio Browser 熱門、搜尋、收藏 |
| `:feature:player` | 迷你播放器、全螢幕播放頁 |
| `:feature:discover` | 線上搜尋歌曲並下載到伺服器 |

#### 每個功能是一條由上到下的流

每個 `:feature:*` 模組都是一條完整的垂直流：從畫面一路串到資料來源，中間每一層只認識下一層的介面。

```
 presentation   Composable 畫面 ──使用者事件──► ViewModel
                     ▲                            │ 呼叫
                     └─── StateFlow<UiState> ─────┤ （Loading / Success / Error）
                                                  ▼
 domain                                       UseCase
                                                  │
                                                  ▼
                                         Repository（介面）
                                                  ▲ 實作
 data                                    RepositoryImpl
                                                  │
                                                  ▼
                              Retrofit API ／ Room DAO ／ DataStore
```

以「音樂庫列出歌曲」為例：

```
LibraryScreen → LibraryViewModel → GetTracksPageUseCase → LibraryRepository
  → LibraryRepositoryImpl → LibraryApi → GET /api/v1/tracks（家用伺服器）
```

各功能的流：

| 功能 | 畫面 | ViewModel | UseCase（主要） | Repository → 資料來源 |
|---|---|---|---|---|
| `session` | `PairScreen`、`LoginScreen`、`SettingsScreen` | `PairViewModel`、`LoginViewModel`、`SettingsViewModel` | `PairServerUseCase`、`LoginUseCase`、`LogoutUseCase`、`GetServerInfoUseCase` | `ConnectionRepository`、`AuthRepository`、`ServerInfoRepository` → `AuthApi`、`ServerInfoApi`、`ConnectionStore`、`TokenStore` |
| `library` | `LibraryScreen`、`ArtistScreen`、`AlbumScreen` | `LibraryViewModel`、`ArtistViewModel`、`AlbumViewModel` | `GetTracksPageUseCase`、`GetArtistsPageUseCase`、`GetAlbumsPageUseCase`、`GetCategoriesUseCase` | `LibraryRepository` → `LibraryApi`（家用伺服器） |
| `playlist` | `MeScreen`、`PlaylistScreen`、加入播放清單選單 | `PlaylistsViewModel`、`PlaylistViewModel`、`AddToPlaylistViewModel` | `GetPlaylistsUseCase`、`CreatePlaylistUseCase`、`AddToPlaylistUseCase`、`MovePlaylistTrackUseCase` | `PlaylistRepository` → `PlaylistApi`（家用伺服器） |
| `podcast` | `PodcastsScreen`、`PodcastDetailScreen`、`PodcastHistoryScreen` | `PodcastsViewModel`、`PodcastDetailViewModel`、`PodcastHistoryViewModel` | `SearchPodcastsUseCase`、`SubscribePodcastUseCase`、`FetchFeedPageUseCase`、`PlayEpisodesUseCase` | `PodcastRepository` → `ITunesApi`、`RssParser`、Room（`PodcastDao`、`EpisodeDao`、`EpisodeHistoryDao`） |
| `radio` | `RadioScreen` | `RadioViewModel` | `SearchStationsUseCase`、`ToggleFavoriteStationUseCase` | `RadioRepository` → `RadioBrowserApi`、Room（`StationDao`） |
| `discover` | `DiscoverScreen` | `DiscoverViewModel` | `SearchOnlineUseCase`、`AddToLibraryUseCase`、`GetDownloadsUseCase` | `DiscoverRepository` → `DiscoverApi`（家用伺服器） |
| `player` | `MiniPlayer`、`NowPlayingSheet` | `PlayerViewModel` | —（沒有 domain／data） | 直接使用 `:core:playback` 的 `PlayerController` |

每條流在模組內對應到四個 package：

| package | 內容 |
|---|---|
| `presentation` | Compose 畫面與 `@HiltViewModel` |
| `domain` | model、repository 介面、UseCase（純 Kotlin，不依賴 AndroidX） |
| `data` | Retrofit API、DTO、Room 存取、repository 實作 |
| `di` | 該模組自己的 Hilt module，把 repository 介面綁到實作 |

主要設計：

- **功能之間幾乎不互相依賴**：唯一例外是 `playlist → library`（共用歌曲資料與歌曲列元件）。
- **虛擬主機 `https://server.musicstream/`**：所有指向家用伺服器的 URL 都用這個固定名稱，由攔截器改寫成目前可用的位址。切換 Wi-Fi／4G 時播放器的 URI 不用變，快取也保持穩定。
- **單一 TrustManager**：憑證符合伺服器的 pin 就信任，否則交給系統 CA。一個 OkHttpClient 同時供 Retrofit、ExoPlayer 和 Coil 使用。
- **播放事件擴充點**：`:core:playback` 不認識 Podcast 和電台；功能模組用 Hilt `@IntoSet` 註冊 `PlaybackEventListener`，例如記錄 Podcast 進度。
