// Package domain holds the library's entities and business rules: tracks,
// the artist/album views aggregated from them, metadata curation and
// duplicate detection. It depends only on the shared kernel.
package domain

import (
	"crypto/sha1"
	"encoding/hex"
	"musicstream/internal/shared/trackmeta"
	"strings"
	"time"
)

// TrackID identifies a track. It survives renames/moves of the file because
// the scanner re-attaches moved files by content hash.
type TrackID int64

// AudioFormat is the container/codec family of a file.
type AudioFormat string

const (
	FormatMP3  AudioFormat = "mp3"
	FormatAAC  AudioFormat = "aac"
	FormatFLAC AudioFormat = "flac"
	FormatOGG  AudioFormat = "ogg"
	FormatOpus AudioFormat = "opus"
	FormatWAV  AudioFormat = "wav"
	FormatWMA  AudioFormat = "wma"
)

// FormatFromExt maps a file extension to an AudioFormat ("" if unsupported).
func FormatFromExt(ext string) AudioFormat {
	switch strings.ToLower(strings.TrimPrefix(ext, ".")) {
	case "mp3":
		return FormatMP3
	case "m4a", "aac", "mp4":
		return FormatAAC
	case "flac":
		return FormatFLAC
	case "ogg", "oga":
		return FormatOGG
	case "opus":
		return FormatOpus
	case "wav":
		return FormatWAV
	case "wma":
		return FormatWMA
	}
	return ""
}

// NativelyPlayableOnAndroid reports whether ExoPlayer can decode the format
// without server-side transcoding.
func (f AudioFormat) NativelyPlayableOnAndroid() bool {
	return f != FormatWMA && f != ""
}

// MIMEType returns the Content-Type used when streaming the raw file.
func (f AudioFormat) MIMEType() string {
	switch f {
	case FormatMP3:
		return "audio/mpeg"
	case FormatAAC:
		return "audio/mp4"
	case FormatFLAC:
		return "audio/flac"
	case FormatOGG:
		return "audio/ogg"
	case FormatOpus:
		return "audio/opus"
	case FormatWAV:
		return "audio/wav"
	case FormatWMA:
		return "audio/x-ms-wma"
	}
	return "application/octet-stream"
}

// Track is a single audio file of the library.
type Track struct {
	ID          TrackID
	Path        string // relative to the library root, slash separated
	Title       string
	Artist      string
	Album       string
	AlbumArtist string
	TrackNo     int
	Year        int
	Genre       string
	Duration    time.Duration
	BitrateKbps int
	Size        int64
	ModTime     time.Time
	Format      AudioFormat
	ContentHash string // hex SHA-256 of the whole file
	HasArtwork  bool   // embedded picture present
	MetaSource  trackmeta.Source
	UserEdited  bool // metadata curated by the user; scans must not overwrite it
	AddedAt     time.Time
	UpdatedAt   time.Time
}

// GroupingArtist is the artist a track is listed under.
func (t Track) GroupingArtist() string {
	if t.AlbumArtist != "" {
		return t.AlbumArtist
	}
	if t.Artist != "" {
		return t.Artist
	}
	return trackmeta.UnknownArtist
}

// ArtistID returns the stable, case-insensitive identifier of the grouping artist.
func (t Track) ArtistID() ArtistID { return NewArtistID(t.GroupingArtist()) }

// AlbumID returns the stable identifier of the track's album ("" if none).
func (t Track) AlbumID() AlbumID {
	if t.Album == "" {
		return ""
	}
	return NewAlbumID(t.GroupingArtist(), t.Album)
}

// ArtistID / AlbumID are derived keys: artists and albums are not stored
// separately but aggregated from tracks.
type (
	ArtistID string
	AlbumID  string
)

func NewArtistID(name string) ArtistID {
	return ArtistID(shortHash("artist", foldKey(name)))
}

func NewAlbumID(artist, album string) AlbumID {
	return AlbumID(shortHash("album", foldKey(artist)+"\x00"+foldKey(album)))
}

func foldKey(s string) string { return strings.ToLower(strings.TrimSpace(s)) }

func shortHash(ns, s string) string {
	sum := sha1.Sum([]byte(ns + ":" + s))
	return hex.EncodeToString(sum[:8])
}

// Artist is an aggregate view over tracks.
type Artist struct {
	ID             ArtistID
	Name           string
	TrackCount     int
	AlbumCount     int
	ArtworkTrackID TrackID // a track with embedded artwork, 0 if none
}

// Album is an aggregate view over tracks.
type Album struct {
	ID             AlbumID
	Title          string
	Artist         string
	ArtistID       ArtistID
	Year           int
	TrackCount     int
	Duration       time.Duration
	ArtworkTrackID TrackID
}

// KeepCuration carries user-curated metadata of the previous version of a
// track over to its rescanned version: user edits always win over tags.
func (t *Track) KeepCuration(prev Track) {
	if !prev.UserEdited {
		return
	}
	t.Title, t.Artist, t.Album, t.AlbumArtist = prev.Title, prev.Artist, prev.Album, prev.AlbumArtist
	t.TrackNo, t.Year, t.Genre = prev.TrackNo, prev.Year, prev.Genre
	t.UserEdited = true
}
