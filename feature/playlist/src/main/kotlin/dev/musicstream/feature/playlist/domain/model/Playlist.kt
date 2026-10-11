package dev.musicstream.feature.playlist.domain.model

import dev.musicstream.feature.library.domain.model.Track

data class PlaylistSummary(val id: Long, val name: String, val trackCount: Int)

/** A playlist is an ordered list of library tracks (a track may appear twice). */
data class Playlist(val id: Long, val name: String, val tracks: List<Track>)
