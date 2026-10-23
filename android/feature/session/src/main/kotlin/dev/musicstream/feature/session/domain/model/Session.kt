package dev.musicstream.feature.session.domain.model

/** Which top-level flow the app should show. */
enum class SessionState { NEEDS_PAIRING, NEEDS_LOGIN, READY }

data class ServerInfo(
    val version: String,
    val trackCount: Int,
    val scanning: Boolean,
    val transcodeAvailable: Boolean,
)
