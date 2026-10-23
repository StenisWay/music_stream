# MusicStream API 文件

| 項目 | 內容 |
|---|---|
| 版本 | 1.0（2026-10-11，依程式碼整理） |
| 伺服器 | `backend/`（Go，`net/http` ServeMux） |
| 客戶端 | `android/`（Retrofit + kotlinx.serialization） |
| 相關文件 | [規格書 §6](SPEC.md#6-api-規格restapiv1)（摘要版）、[架構統整](ARCHITECTURE.md) |

本文件以程式碼為準。§1～§3 是 API 參考，§4 是和 Android App 的逐項比對結果。

---

## 1. 通用約定

### 1.1 傳輸

- **HTTPS**，自簽憑證（ECDSA P-256）。App 用建置時寫入的 SPKI pin（`android/server.properties`）驗證伺服器，不看 CA 和主機名稱
- 伺服器只監聽 Tailscale 位址：`https://<machine>.<tailnet>.ts.net:8443`（`100.x.y.z`）。內網和本機都連不到
- App 內所有請求都送往虛擬主機 `https://server.musicstream/`，由 `ServerUrlInterceptor` 改寫成上面的實際位址
- JSON 欄位一律 **camelCase**；時間是 **Unix 毫秒**（時間為空時回 `0`）；`expiresIn` 例外，單位是秒

### 1.2 驗證

| 路由 | 是否需要 token |
|---|---|
| `GET /healthz` | 不需要 |
| `POST /api/v1/auth/login`、`/refresh`、`/logout` | 不需要 |
| 其他 `/api/v1/*` | 需要 `Authorization: Bearer <accessToken>` |

- 沒帶 token：`401 unauthenticated`，`WWW-Authenticate: Bearer realm="musicstream"`
- token 無效或過期：`401 invalid_token`，`WWW-Authenticate` 帶 `error="invalid_token"`
- Access token 為 JWT HS256，效期 1 小時；Refresh token 效期 90 天，每次使用後輪替

### 1.3 請求 body

- 上限 **1 MB**，超過回 `400 invalid_json`
- **拒絕未知欄位**（`DisallowUnknownFields`）：多送一個欄位就會回 `400 invalid_json`

### 1.4 分頁

適用於 `/tracks`、`/artists`、`/albums`。

| 參數 | 預設 | 規則 |
|---|---|---|
| `limit` | 50 | `≤ 0` 時用 50；`> 500` 時壓成 500 |
| `offset` | 0 | |

回應格式 `Page<T>`：

```json
{ "items": [ ... ], "total": 1104, "limit": 50, "offset": 0 }
```

### 1.5 錯誤

格式：`{"error":{"code":"not_found","message":"resource not found"}}`

| HTTP | code | 何時發生 |
|---|---|---|
| 400 | `invalid_argument` | 參數不合法（id 不是正整數、sort 值錯誤、搜尋字串空白、播放清單名稱空白等） |
| 400 | `invalid_json` | body 無法解析、有未知欄位、超過 1 MB |
| 401 | `unauthenticated` | 沒帶 token；帳密錯誤；refresh token 無效 |
| 401 | `invalid_token` | access token 無效或過期 |
| 404 | `not_found` | 資源不存在；伺服器沒設定 inbox 時呼叫 `/library/ingest` |
| 409 | `conflict` | 掃描進行中又呼叫 `/library/scan` |
| 415 | `unsupported` | 要求 `?transcode=mp3` 但伺服器沒有 ffmpeg |
| 429 | `rate_limited` | 登入或 refresh 太頻繁（每個 IP 連續 5 次，之後每 12 秒回補 1 次）；帶 `Retry-After: 60` |
| 500 | `internal` | 其他錯誤（細節只寫在伺服器 log） |
| 502 | `upstream` | `/discover/search` 時線上來源沒有回應（網路問題，或網站改版、yt-dlp 需要更新） |
| 503 | `unavailable` | `/discover/*`：伺服器沒有 yt-dlp／ffmpeg，或沒設定 inbox |

---

## 2. 資料結構

### 2.1 Track

```json
{
  "id": 1104, "title": "沙耶の眠れるレクイエム", "artist": "風音",
  "album": "沙耶の眠れるレクイエム／Saya's Song", "albumArtist": "",
  "artistId": "4eaa4cdd9ae1a44e", "albumId": "1cd33b82a438459e",
  "trackNo": 5, "year": 0, "genre": "Other",
  "durationMs": 393288, "bitrateKbps": 320, "size": 15754870, "format": "mp3",
  "streamUrl": "/api/v1/tracks/1104/stream", "artworkUrl": "/api/v1/tracks/1104/artwork",
  "hasArtwork": true, "playable": true,
  "metaSource": "tags", "userEdited": false, "addedAt": 1791299740488,
  "type": "歌曲", "region": "日本"
}
```

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | int64 | |
| `artistId` / `albumId` | string | 由名稱推得的 16 位 hex，不分大小寫。**`albumId` 沒有專輯時省略** |
| `streamUrl` | string | 相對路徑。格式 Android 不能直接解碼（WMA）且伺服器有 ffmpeg 時，會自動帶上 `?transcode=mp3` |
| `artworkUrl` | string | 相對路徑；**沒有封面時省略** |
| `playable` | bool | Android 可直接播放，或伺服器能轉檔時為 `true` |
| `metaSource` | string | metadata 來源（例如 `tags`） |
| `userEdited` | bool | 被 `PATCH` 修正過；之後的掃描不會覆蓋 |
| `type` / `region` | string | 檔案所在的 `library/<類型>/<語區>/` 資料夾；檔案不在這兩層裡時省略 |

### 2.2 Artist

```json
{ "id": "4eaa4cdd9ae1a44e", "name": "風音", "trackCount": 12, "albumCount": 3,
  "artworkUrl": "/api/v1/tracks/1104/artwork" }
```

`artworkUrl` 取自該歌手某一首有封面的歌，沒有時省略。

### 2.3 Album

```json
{ "id": "1cd33b82a438459e", "title": "沙耶の眠れるレクイエム／Saya's Song",
  "artist": "風音", "artistId": "4eaa4cdd9ae1a44e", "year": 0,
  "trackCount": 6, "durationMs": 1800000, "artworkUrl": "/api/v1/tracks/1104/artwork" }
```

### 2.4 TokenPair

```json
{ "tokenType": "Bearer", "accessToken": "eyJ...", "expiresIn": 3599,
  "refreshToken": "<id>.<secret>", "refreshExpiresAt": 1799075740488 }
```

### 2.5 Playlist

```jsonc
// PlaylistSummary（列表用）
{ "id": 3, "name": "通勤", "trackCount": 25, "updatedAt": 1791299740488 }
// Playlist（含歌曲）
{ "id": 3, "name": "通勤", "tracks": [ Track, ... ], "updatedAt": 1791299740488 }
```

---

## 3. 端點

### 3.1 系統

#### `GET /healthz`（公開，路徑**不在** `/api/v1` 底下）

```json
{ "status": "ok", "version": "1.0.0" }
```

App 用它平行探測各個候選位址。

#### `GET /api/v1/server/info`

```json
{ "version": "1.0.0", "trackCount": 1104, "scanning": false,
  "lastScanAt": 1791299740488, "transcodeAvailable": true }
```

### 3.2 帳號

| Method | Path | Request | 成功回應 |
|---|---|---|---|
| POST | `/api/v1/auth/login` | `{username, password, deviceName}` | 200 `TokenPair` |
| POST | `/api/v1/auth/refresh` | `{refreshToken}` | 200 新的 `TokenPair`（舊的 refresh token 作廢） |
| POST | `/api/v1/auth/logout` | `{refreshToken}` | 204 |

- `login`、`refresh` 有限流（見 §1.5 的 429）
- 帳密錯誤、refresh token 無效或過期：`401 unauthenticated`
- 已輪替掉的 refresh token 被再次使用時，撤銷該使用者所有 session

### 3.3 曲庫

#### `GET /api/v1/tracks` → `Page<Track>`

| 參數 | 說明 |
|---|---|
| `q` | 子字串搜尋歌名、歌手、專輯（不分大小寫） |
| `artistId`、`albumId` | 篩選 |
| `type`、`region` | 依曲庫資料夾 `library/<類型>/<語區>/` 篩選，見 §3.3「分類」；可只給其中一個 |
| `sort` | `title`（預設）、`artist`、`album`、`-addedAt`（最近加入）；其他值回 400 |
| `limit`、`offset` | 見 §1.4 |

#### `GET /api/v1/tracks/{id}` → `Track`

#### `PATCH /api/v1/tracks/{id}` → 修改後的 `Track`

Body 欄位全部選填，只改有送的欄位：

```json
{ "title": "...", "artist": "...", "album": "...", "albumArtist": "...",
  "trackNo": 1, "year": 2008, "genre": "..." }
```

- `title` 不可為空字串；`trackNo` 範圍 0～999；`year` 範圍 0～9999；違反時回 400
- 修改後 `userEdited` 變成 `true`

#### `GET /api/v1/artists` → `Page<Artist>`

參數：`q`、`type`、`region`、`limit`、`offset`

有分類篩選時，`trackCount`／`albumCount` 只算該分類裡的歌；同一位歌手同時有歌曲和純音樂時，兩邊都會列出。

#### `GET /api/v1/artists/{id}`

```json
{ "artist": Artist, "albums": [ Album, ... ] }
```

`albums` 最多 500 張，沒有分頁。

#### `GET /api/v1/albums` → `Page<Album>`

參數：`artistId`、`q`、`type`、`region`、`limit`、`offset`

#### `GET /api/v1/categories`

曲庫資料夾的前兩層（和電腦上的資料夾一致），只列出有歌的組合，依固定順序（歌曲→純音樂；華語→日本→韓國→歐美→其他）：

```json
{ "categories": [ { "type": "歌曲", "region": "華語", "trackCount": 330, "artistCount": 134 }, ... ] }
```

- `type`：`歌曲`（有人聲）、`純音樂`
- `region`：`華語`、`日本`、`韓國`、`歐美`、`其他`
- 其他值傳給 `type`／`region` 參數時回 400

#### `GET /api/v1/albums/{id}`

```json
{ "album": Album, "tracks": [ Track, ... ] }
```

`tracks` 依曲序排列，最多 500 首。

#### `GET /api/v1/search`

| 參數 | 說明 |
|---|---|
| `q` | 必填，空白時回 400 |
| `limit` | 每一類的筆數，1～50；**不填、≤ 0 或 > 50 時一律用 20**（不是壓成 50） |

```json
{ "tracks": [ Track ], "artists": [ Artist ], "albums": [ Album ] }
```

#### `GET /api/v1/library/duplicates`

```json
{ "groups": [ { "reason": "identical", "tracks": [ Track, ... ] },
              { "reason": "same_song", "tracks": [ Track, ... ] } ] }
```

- `identical`：內容完全相同（SHA-256）
- `same_song`：同歌手同歌名的不同上傳版本（Remix、INST、Live 不算）

#### `POST /api/v1/library/scan`

- 202 `{"status":"scan started"}`，在背景執行完整掃描
- 掃描進行中：`409 conflict`

#### `POST /api/v1/library/ingest`

立即把 inbox 裡的檔案分類進曲庫（同步執行，完成才回應）。

```json
{ "audio": 3, "duplicates": 1, "artwork": 0, "misc": 0, "failed": 0 }
```

伺服器沒設定 inbox 時：`404 not_found`。

### 3.4 串流與封面

#### `GET /api/v1/tracks/{id}/stream`

| 模式 | 行為 |
|---|---|
| 原始檔（預設） | 支援 `Range`（206）、`If-Range`、`If-Modified-Since`、`HEAD`；`Cache-Control: private, max-age=3600` |
| `?transcode=mp3` | ffmpeg 即時轉成 `audio/mpeg`；`Accept-Ranges: none`（**不能拖動進度**）；`Cache-Control: no-store`；沒有 ffmpeg 時回 415 |

#### `GET /api/v1/tracks/{id}/artwork`

- 回傳圖片原始位元組，`Content-Type` 依圖片格式
- `ETag` 加 `Cache-Control: private, max-age=604800`（7 天）；帶 `If-None-Match` 且相符時回 304
- 封面來源優先順序：內嵌圖 → 資料夾內的 cover/folder 圖 → `Artwork/` 內第一張；都沒有時回 404

### 3.5 播放清單

「收藏的歌曲」（`/favorites`）已移除，改用播放清單。

| Method | Path | Request | 回應 |
|---|---|---|---|
| GET | `/api/v1/playlists` | — | 200 `{"items":[PlaylistSummary]}`（沒有分頁）。可加 `?containsTrack={trackId}`，每筆會多一個 `containsTrack: bool` |
| POST | `/api/v1/playlists` | `{name, trackIds?}` | **201** `Playlist` |
| GET | `/api/v1/playlists/{id}` | — | 200 `Playlist` |
| PATCH | `/api/v1/playlists/{id}` | `{name}` | 200 `Playlist` |
| DELETE | `/api/v1/playlists/{id}` | — | 204 |
| PUT | `/api/v1/playlists/{id}/tracks` | `{trackIds}` | 200 `Playlist`（整份替換，用於排序或移除） |
| POST | `/api/v1/playlists/{id}/tracks` | `{trackIds}` | 200 `Playlist`（附加到最後） |
| DELETE | `/api/v1/playlists/{id}/tracks/{trackId}` | — | 200 `Playlist`（移除這首歌的所有出現；本來就不在清單裡也回 200） |

- 名稱去頭尾空白後 1～100 字
- 一份清單最多 5,000 首；同一首歌可以重複出現
- `trackIds` 裡有不存在的 id 時回 400
- 只能存取自己的清單，別人的清單回 404

### 3.6 線上找歌（discover）

從 YouTube／SoundCloud 搜尋歌曲。加入音樂庫時，伺服器用 yt-dlp 下載成 mp3，寫入歌手、歌名、專輯標籤和封面後放進 inbox，再由收錄流程自動分類進曲庫（通常 10～30 秒）。下載一次只跑一首，其他的排隊。工作紀錄只存在記憶體裡，伺服器重啟後就清空，但已經下載完的歌曲不受影響。

#### `GET /api/v1/discover/search?q=&source=youtube|soundcloud&limit=`

- `source` 預設 `youtube`；`limit` 預設和上限都是 30
- 回應 `{"items":[Candidate]}`：

```json
{ "source": "youtube", "url": "https://www.youtube.com/watch?v=DYptgVvkVLQ",
  "title": "周杰倫 Jay Chou【晴天 Sunny Day】-Official Music Video", "uploader": "周杰倫 Jay Chou",
  "durationSec": 319, "thumbnailUrl": "https://i.ytimg.com/…", "artist": "周杰倫", "track": "晴天" }
```

`artist`／`track` 是伺服器從標題猜出來的歸檔名稱，會參考曲庫裡已經有的歌手。直播會被濾掉。

#### `POST /api/v1/discover/downloads`

Request `{url, artist, title, album?}` → **202** `Job`

- `url` 必須是 https 的 YouTube（youtube.com、youtu.be、music.youtube.com）或 SoundCloud 網址，其他網址一律回 400
- `title` 去頭尾空白後不可空白
- 同一個網址還在排隊或下載中時，直接回傳原本的工作
- 直播或超過 1 小時的影片會下載失敗

#### `GET /api/v1/discover/downloads` → `{"items":[Job]}`（新的在前，保留最近 50 筆已結束的工作）

```json
{ "id": 1, "url": "https://www.youtube.com/watch?v=DYptgVvkVLQ", "artist": "周杰倫", "title": "晴天",
  "album": "葉惠美", "status": "queued|downloading|done|failed", "error": "ERROR: … (failed 才有)",
  "createdAt": 1792107087642, "updatedAt": 1792107100997 }
```

`done` 表示檔案已經放進 inbox，收錄完成後就會出現在 `GET /tracks`。

---

## 4. 與 Android App 的比對

比對範圍：

- 伺服器：`backend/internal/feature/*/presentation/handler.go`、`library/presentation/dto.go`、`platform/httpx`
- App：`LibraryApi.kt`、`LibraryDtos.kt`、`PlaylistApi.kt`、`SessionApis.kt`、`TokenRefreshApi.kt`、`ConnectionRepositoryImpl.kt`（`/healthz`）、`PlaybackService`（串流）、`MusicStreamApp`（Coil 封面）

### 4.1 結論

伺服器共 28 個端點，**App 用到的 23 個全部對得上**：路徑、HTTP 方法、參數名稱、request body、回應欄位與型別都一致，不會造成執行時錯誤。不一致的地方都在「App 還沒用到」或「文件寫得不精確」，沒有會壞掉的問題。

App 的 JSON 設定是 `ignoreUnknownKeys = true`，所以伺服器多回的欄位不會出錯；App 送出的 body 也沒有多餘欄位，不會踩到伺服器的「拒絕未知欄位」。

### 4.2 逐項比對

✅ 一致　⬜ 伺服器有、App 沒用

| 端點 | App 方法 | 結果 | 備註 |
|---|---|---|---|
| `GET /healthz` | `ConnectionRepositoryImpl.probe` | ✅ | 只看 HTTP 狀態碼，不解析 body |
| `GET /server/info` | `ServerInfoApi.serverInfo` | ✅ | App 沒讀 `lastScanAt` |
| `POST /auth/login` | `AuthApi.login` | ✅ | `deviceName` = 廠牌加型號 |
| `POST /auth/refresh` | `TokenRefreshApi.refresh` | ✅ | 由 `TokenAuthenticator` 收到 401 時呼叫；refresh 回 401 就清除 token |
| `POST /auth/logout` | `AuthApi.logout` | ✅ | 不帶 Bearer（`AuthInterceptor` 跳過 `/auth/*`），伺服器也不要求 |
| `GET /tracks` | `LibraryApi.tracks` | ✅ | `sort` 的 4 個值與伺服器完全相同 |
| `GET /tracks/{id}` | — | ⬜ | |
| `PATCH /tracks/{id}` | — | ⬜ | App 尚未實作編輯 metadata |
| `GET /tracks/{id}/stream` | ExoPlayer（`@ServerClient`） | ✅ | 直接用 Track 的 `streamUrl`，轉檔參數由伺服器決定 |
| `GET /tracks/{id}/artwork` | Coil（`@ServerClient`） | ✅ | 同一個 client，會帶 token |
| `GET /artists` | `LibraryApi.artists` | ✅ | |
| `GET /artists/{id}` | `LibraryApi.artist` | ✅ | |
| `GET /albums` | `LibraryApi.albums` | ✅ | |
| `GET /albums/{id}` | `LibraryApi.album` | ✅ | |
| `GET /categories` | `LibraryApi.categories` | ✅ | 曲庫頁的「類型／語區」篩選；`/tracks`、`/artists`、`/albums` 都帶 `type`、`region` |
| `GET /search` | `LibraryApi.search` | ⬜ | App 的「搜尋」分頁已改成線上找歌；在曲庫裡找歌改用曲庫頁的篩選（`GET /tracks?q=`） |
| `GET /discover/search` | `DiscoverApi.search` | ✅ | 固定 `limit=20`；送出搜尋時才查詢，打字時不查 |
| `POST /discover/downloads` | `DiscoverApi.download` | ✅ | |
| `GET /discover/downloads` | `DiscoverApi.downloads` | ✅ | 有工作在進行時每 2 秒輪詢一次 |
| `GET /library/duplicates` | — | ⬜ | App 尚未實作重複檔清單 |
| `POST /library/scan` | — | ⬜ | App 尚未實作手動觸發 |
| `POST /library/ingest` | — | ⬜ | App 尚未實作手動觸發 |
| `GET /playlists` | `PlaylistApi.playlists` | ✅ | |
| `POST /playlists` | `PlaylistApi.createPlaylist` | ✅ | 201 也算成功 |
| `GET /playlists/{id}` | `PlaylistApi.playlist` | ✅ | |
| `PATCH /playlists/{id}` | `PlaylistApi.renamePlaylist` | ✅ | |
| `DELETE /playlists/{id}` | `PlaylistApi.deletePlaylist` | ✅ | |
| `PUT /playlists/{id}/tracks` | `PlaylistApi.replacePlaylistTracks` | ✅ | |
| `POST /playlists/{id}/tracks` | `PlaylistApi.appendPlaylistTracks` | ✅ | |
| `DELETE /playlists/{id}/tracks/{trackId}` | `PlaylistApi.removePlaylistTrack` | ✅ | |

### 4.3 欄位比對

| 結構 | App 有接 | 伺服器有、App 沒接 | 可省略欄位的處理 |
|---|---|---|---|
| Track | id、title、artist、album、albumArtist、artistId、albumId、trackNo、year、durationMs、format、streamUrl、artworkUrl、playable | genre、bitrateKbps、size、hasArtwork、metaSource、userEdited、addedAt | `albumId`、`artworkUrl` 在 App 是 nullable ✅ |
| Artist | 全部 | — | `artworkUrl` nullable ✅ |
| Album | 全部 | — | `artworkUrl` nullable ✅ |
| Page | 全部 | — | |
| TokenPair | 全部 | — | |
| PlaylistSummary | id、name、trackCount | updatedAt | |
| Playlist | id、name、tracks | updatedAt | |
| ServerInfo | version、trackCount、scanning、transcodeAvailable | lastScanAt | |

App 必填（沒有預設值）的欄位只有 Track 的 `id`、`title`、`streamUrl`，以及 TokenPair 全部欄位；伺服器每次都會回傳，沒有風險。

App 實作編輯 metadata 時，需要在 `TrackDto` 加上 `genre`、`userEdited`，畫面才能顯示目前值與「已手動修正」。

### 4.4 錯誤處理比對

App 的 `toAppError()` 只看 HTTP 狀態碼，不解析 `error.code`：

| HTTP | App 對應 | 評估 |
|---|---|---|
| 401 | `Unauthorized` | ✅ 登入時轉成 `InvalidCredentials`；其他 API 先由 `TokenAuthenticator` 自動 refresh |
| 404 | `NotFound` | ✅ |
| 400 / 409 / 415 | `Invalid(message())` | ⚠️ `message()` 是 HTTP 狀態文字（例如 "Bad Request"），不是伺服器回的 `error.message`。目前用到的端點影響不大；做 #13（掃描）時，409「掃描中」最好改成獨立提示 |
| 429 | `TooManyAttempts` | ✅ |
| 其他 | `Server(code)` | ✅ |

### 4.5 文件和程式不一致的地方（2026-10-11 已修正）

| # | 位置 | 原本的問題 | 處理 |
|---|---|---|---|
| D1 | SPEC §6 | `/search` 寫 `limit≤50`；實際上超過 50 會回到預設 20，不是壓成 50 | ✅ SPEC 已改寫 |
| D2 | SPEC §6 | 標題是「`/api/v1`」，但 `/healthz` 實際在根路徑 | ✅ SPEC 已註明 |
| D3 | SPEC §6 | 沒寫 `POST /playlists` 回 201、各狀態碼和錯誤碼的對應 | ✅ SPEC 補上 201；完整對應見本文件 §1.5、§3.5 |
| D4 | Android 4 個檔案的註解 | 指向已不存在的 `backend/internal/adapter/httpapi/dto.go` | ✅ 改成指向本文件與 `feature/*/presentation/` |
| D5 | SPEC §4.3、§4.4 | 實作欄寫 `stream/streamapi`、`auth/credential.JWT`、`authapi` 等舊名稱 | ✅ 改成 `stream/presentation`、`auth/infrastructure.NewJWT`、`auth/presentation` |

### 4.6 順帶發現的伺服器問題（2026-10-11 已修正）

| # | 原本的問題 | 處理 |
|---|---|---|
| B1 | `POST /playlists/{id}/tracks`（附加）只檢查「這次附加的數量」≤ 5,000，沒檢查附加後的總數 | ✅ `AppendTracks` 改成檢查合併後的總數；新增測試 `playlist/application/service_test.go` |
