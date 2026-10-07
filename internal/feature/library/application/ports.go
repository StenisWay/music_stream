package application

import (
	"context"
	"musicstream/internal/shared/trackmeta"
	"time"
)

// FileInfo describes an audio file found in the library folder.
type FileInfo struct {
	Path    string // relative, slash separated
	Size    int64
	ModTime time.Time
}

// MediaSource enumerates and resolves audio files of the library folder.
type MediaSource interface {
	ListAudioFiles(ctx context.Context) ([]FileInfo, error)
	Stat(rel string) (FileInfo, error)
	AbsPath(rel string) (string, error)
	// FindFolderArtwork reports a cover image stored with the album.
	FindFolderArtwork(dirRel string) (string, bool)
}

// ProbeResult is everything that can be learned by reading a file.
type ProbeResult struct {
	Tags        trackmeta.Meta
	Duration    time.Duration
	BitrateKbps int
	HasPicture  bool
}

// Prober extracts tags and technical properties from an audio file.
type Prober interface {
	Probe(absPath string) (ProbeResult, error)
}

// Hasher computes a content hash used to detect moves and duplicates.
type Hasher interface {
	Hash(absPath string) (string, error)
}
