// Package domain holds the ingest model: a plan of file moves into the
// canonical library layout, and the rules that classify inbox files.
package domain

import (
	"musicstream/internal/shared/trackmeta"
	"path/filepath"
	"strings"
)

// Kind classifies a planned move.
type Kind string

const (
	KindAudio     Kind = "audio"
	KindArtwork   Kind = "artwork"
	KindDuplicate Kind = "duplicate"
	KindMisc      Kind = "misc"
)

// Move is a single planned (or executed) file relocation.
type Move struct {
	Kind   Kind             `json:"kind"`
	From   string           `json:"from"`
	To     string           `json:"to"`
	Source trackmeta.Source `json:"source,omitempty"`
	Meta   trackmeta.Meta   `json:"-"`
}

// Plan is the full, side-effect-free result of planning.
type Plan struct {
	Moves []Move
}

// Count returns the number of moves of the given kind.
func (p Plan) Count(k Kind) int {
	n := 0
	for _, m := range p.Moves {
		if m.Kind == k {
			n++
		}
	}
	return n
}

// Layout describes where things go.
type Layout struct {
	SourceRoot     string // messy input tree
	LibraryRoot    string // organised audio
	MiscRoot       string // non-audio files that are not artwork
	DuplicatesRoot string // byte-identical copies
}

var (
	// tempExt are partial downloads/copies that must never be moved.
	tempExt  = map[string]bool{".part": true, ".crdownload": true, ".tmp": true, ".download": true, ".partial": true, ".aria2": true, ".!qb": true}
	audioExt = map[string]bool{".mp3": true, ".wma": true, ".flac": true, ".m4a": true, ".aac": true, ".ogg": true, ".opus": true, ".wav": true}
	imageExt = map[string]bool{".jpg": true, ".jpeg": true, ".png": true, ".bmp": true, ".gif": true, ".webp": true}
)

func ext(path string) string { return strings.ToLower(filepath.Ext(path)) }

// IsIgnored reports files ingest must leave alone: partial downloads and
// hidden files.
func IsIgnored(path string) bool {
	return tempExt[ext(path)] || strings.HasPrefix(filepath.Base(path), ".")
}

// IsAudio reports whether the file is a supported audio format.
func IsAudio(path string) bool { return audioExt[ext(path)] }

// IsImage reports whether the file is cover art or a booklet scan.
func IsImage(path string) bool { return imageExt[ext(path)] }
