// Package trackmeta holds the pure business rules for deriving a track's
// descriptive metadata (title / artist / album) from embedded tags, the file
// name and the folder it lives in. It has no I/O dependencies so it can be
// shared by the library scanner and the offline organizer tool.
package trackmeta

import (
	"strings"
	"unicode"
)

// Meta is the descriptive metadata of a single audio track.
type Meta struct {
	Title       string
	Artist      string
	Album       string
	AlbumArtist string
	TrackNo     int
	Year        int
	Genre       string
}

// Source records where the resolved metadata came from; useful for reports
// and for prioritising tracks that still need manual curation.
type Source string

const (
	SourceTags     Source = "tags"
	SourceFilename Source = "filename"
	SourceMixed    Source = "mixed"
)

// UnknownArtist is used when no artist can be inferred.
const UnknownArtist = "Unknown Artist"

// GroupingArtist returns the artist a track should be grouped under
// (album artist wins so compilations are not split across folders).
func (m Meta) GroupingArtist() string {
	if m.AlbumArtist != "" {
		return m.AlbumArtist
	}
	if m.Artist != "" {
		return m.Artist
	}
	return UnknownArtist
}

// junkValues are placeholder strings written by rippers/players that carry
// no information.
var junkValues = map[string]struct{}{
	"unknown": {}, "unknown artist": {}, "unknown album": {}, "未知": {},
	"未知的專輯": {}, "未知的演出者": {}, "未知歌手": {}, "未知专辑": {}, "未知艺术家": {},
	"various": {}, "various artists": {}, "群星": {}, "track": {}, "untitled": {},
}

// CleanValue trims and normalises a tag value, returning "" for junk.
func CleanValue(s string) string {
	s = strings.Map(func(r rune) rune {
		switch {
		case r == '\u3000' || r == '\u00a0':
			return ' '
		case r == 0 || r == '\ufeff' || r == '\u00ad':
			return -1
		case unicode.IsControl(r):
			return -1
		}
		return r
	}, s)
	s = collapseSpaces(strings.TrimSpace(s))
	if _, junk := junkValues[strings.ToLower(s)]; junk {
		return ""
	}
	if strings.HasSuffix(strings.ToLower(s), "vevo") { // YouTube channel names
		return ""
	}
	return s
}

func collapseSpaces(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

// IsCJK reports whether r is a Chinese/Japanese/Korean script rune.
func IsCJK(r rune) bool {
	return unicode.In(r, unicode.Han, unicode.Hiragana, unicode.Katakana, unicode.Hangul)
}

func containsCJK(s string) bool {
	for _, r := range s {
		if IsCJK(r) {
			return true
		}
	}
	return false
}
