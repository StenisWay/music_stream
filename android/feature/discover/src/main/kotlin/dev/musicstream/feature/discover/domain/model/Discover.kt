package dev.musicstream.feature.discover.domain.model

/** Online site the home server can search and download from. */
enum class OnlineSource(val apiValue: String) { YOUTUBE("youtube"), SOUNDCLOUD("soundcloud") }

/** A song found online; [artist]/[title] are the server's guess for filing it. */
data class OnlineSong(
    val url: String,
    val source: OnlineSource,
    val videoTitle: String,
    val uploader: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val artist: String,
    val title: String,
)

/** What the downloaded file is tagged with, and so where it lands in the library. */
data class SongInfo(val artist: String, val title: String, val album: String = "")

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }

/** A server-side download; DONE means it is in the inbox and appears in the library within seconds. */
data class DownloadJob(
    val id: Long,
    val url: String,
    val info: SongInfo,
    val status: DownloadStatus,
    val error: String?,
) {
    val active: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING
}
