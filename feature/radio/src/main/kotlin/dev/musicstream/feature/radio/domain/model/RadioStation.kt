package dev.musicstream.feature.radio.domain.model

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val country: String,
    val tags: List<String>,
    val faviconUrl: String?,
    val codec: String,
    val bitrateKbps: Int,
    val favorite: Boolean,
)
