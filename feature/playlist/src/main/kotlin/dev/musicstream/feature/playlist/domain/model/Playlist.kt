package dev.musicstream.feature.playlist.domain.model

import dev.musicstream.feature.library.domain.model.Track

data class PlaylistSummary(val id: Long, val name: String, val trackCount: Int)

/** A playlist offered in the picker, and whether it already holds the track. */
data class PlaylistChoice(val playlist: PlaylistSummary, val containsTrack: Boolean)

/** A playlist is an ordered list of library tracks (a track may appear twice). */
data class Playlist(val id: Long, val name: String, val tracks: List<Track>)
