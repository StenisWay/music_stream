package application

import (
	"context"
	"musicstream/internal/feature/ingest/domain"
	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/shared/trackmeta"
	"time"
)

// FileEntry is a regular file found under the source root.
type FileEntry struct {
	Path    string // absolute
	Rel     string // relative to the source root
	Size    int64
	ModTime time.Time
}

// FileSystem abstracts every filesystem side effect the use cases need.
type FileSystem interface {
	ListFiles(ctx context.Context, root string) ([]FileEntry, error)
	Exists(path string) bool
	Move(src, dst string) error
	Hash(path string) (string, error)
	// RemoveEmptyDirs deletes empty directories below root; root itself is kept.
	RemoveEmptyDirs(root string) (int, error)
	// ExistingChild finds an entry of dir whose name equals name ignoring
	// case, so new tracks join existing folders ("clannad" -> "CLANNAD").
	ExistingChild(dir, name string) (string, bool)
}

// MetaReader reads already-sanitised embedded tags. Files without tags
// return a zero Meta and a nil error.
type MetaReader interface {
	ReadMeta(path string) (trackmeta.Meta, error)
}

// MoveLog persists executed moves so they can be undone.
type MoveLog interface {
	Append(m domain.Move) error
	Close() error
}

// MoveLogFactory opens a new undo log for one ingest run and returns its path.
type MoveLogFactory func(started time.Time) (MoveLog, string, error)

// Catalog is what ingest needs from the library: the existing tracks, whose
// artists improve filename parsing and whose hashes reveal duplicates.
type Catalog interface {
	All(ctx context.Context) ([]library.Track, error)
}

// CatalogRefresher re-indexes the library after files were moved into it.
type CatalogRefresher interface {
	Refresh(ctx context.Context) error
}
