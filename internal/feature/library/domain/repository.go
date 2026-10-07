package domain

import (
	"context"

	"musicstream/internal/shared"
)

// TrackRepository persists tracks and answers catalog queries. Artists and
// albums are aggregated from tracks, not stored separately.
type TrackRepository interface {
	Get(ctx context.Context, id TrackID) (Track, error)
	List(ctx context.Context, f TrackFilter, p shared.Page) (shared.PageResult[Track], error)
	ListByIDs(ctx context.Context, ids []TrackID) ([]Track, error)
	// All returns every track; used by the scanner, ingest and duplicate finder.
	All(ctx context.Context) ([]Track, error)
	Upsert(ctx context.Context, t Track) (Track, error)
	Delete(ctx context.Context, ids []TrackID) error
	Count(ctx context.Context) (int, error)

	ListArtists(ctx context.Context, query string, p shared.Page) (shared.PageResult[Artist], error)
	GetArtist(ctx context.Context, id ArtistID) (Artist, error)
	ListAlbums(ctx context.Context, artist ArtistID, query string, p shared.Page) (shared.PageResult[Album], error)
	GetAlbum(ctx context.Context, id AlbumID) (Album, error)
}
