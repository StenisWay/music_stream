// Package application holds the discover use cases: searching online
// sources and running download jobs that drop finished songs into the inbox,
// from where the ingest feature files them into the library.
package application

import (
	"context"

	"musicstream/internal/feature/discover/domain"
)

// Fetcher searches and downloads from online sources (yt-dlp).
type Fetcher interface {
	// Available is false when the downloader is not installed.
	Available() bool
	Search(ctx context.Context, src domain.Source, query string, limit int) ([]domain.Candidate, error)
	// Download fetches url as an audio file tagged with info in a private
	// work folder. cleanup removes everything Download left behind.
	Download(ctx context.Context, url string, info domain.TrackInfo) (file string, cleanup func(), err error)
}

// Inbox is the folder ingest watches.
type Inbox interface {
	// Deliver moves file into the inbox under name (made unique if taken).
	// The file must appear complete: ingest must never see a partial copy.
	Deliver(file, name string) error
}

// ArtistCatalog lists the artists already in the library; they help tell
// the artist from the title in "A - B" video titles.
type ArtistCatalog interface {
	KnownArtists(ctx context.Context) ([]string, error)
}
