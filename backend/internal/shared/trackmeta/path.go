package trackmeta

import (
	"fmt"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

const maxSegmentBytes = 150

var unsafePathChars = strings.NewReplacer(
	"/", "／", "\\", "＼", ":", "：", "*", "＊", "?", "？",
	"\"", "＂", "<", "＜", ">", "＞", "|", "｜",
)

// SanitizeSegment makes s safe to use as a single path segment on Linux,
// Windows and Android storage.
func SanitizeSegment(s string) string {
	s = strings.TrimSpace(unsafePathChars.Replace(collapseSpaces(s)))
	s = strings.Trim(s, ". ")
	for len(s) > maxSegmentBytes {
		_, size := utf8.DecodeLastRuneInString(s)
		s = s[:len(s)-size]
	}
	s = strings.TrimSpace(s)
	if s == "" {
		return "_"
	}
	return s
}

// LibrarySegments returns the folder and file names of a track inside the
// organised library: artist folder, album folder ("" when the album is
// unknown) and "<NN - >Title<ext>".
func LibrarySegments(m Meta, ext string) (artist, album, file string) {
	file = SanitizeSegment(m.Title)
	if m.TrackNo > 0 && m.Album != "" {
		file = fmt.Sprintf("%02d - %s", m.TrackNo, file)
	}
	if m.Album != "" {
		album = SanitizeSegment(m.Album)
	}
	return SanitizeSegment(m.GroupingArtist()), album, file + strings.ToLower(ext)
}

// LibraryPath returns the location of a track below its category folder
// (see Category.Dir): <Artist>/<Album>/<NN - Title><ext>, or
// <Artist>/<Title><ext> when the album is unknown.
func LibraryPath(m Meta, ext string) string {
	artist, album, file := LibrarySegments(m, ext)
	if album == "" {
		return filepath.Join(artist, file)
	}
	return filepath.Join(artist, album, file)
}
