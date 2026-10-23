// Package application holds the library use cases: catalog queries,
// metadata curation, duplicate reports and the scanner that keeps the
// catalog in sync with the music folder. It talks to the outside world only
// through domain.TrackRepository and the ports in ports.go.
package application

import (
	"context"
	"fmt"
	"strings"

	"musicstream/internal/feature/library/domain"
	"musicstream/internal/shared"
)

// Catalog exposes catalog queries and metadata curation.
type Catalog struct {
	tracks domain.TrackRepository
}

func NewCatalog(tracks domain.TrackRepository) *Catalog {
	return &Catalog{tracks: tracks}
}

func (s *Catalog) ListTracks(ctx context.Context, f domain.TrackFilter, p shared.Page) (shared.PageResult[domain.Track], error) {
	if f.Sort == "" {
		f.Sort = domain.SortByTitle
	}
	if !f.Sort.Valid() {
		return shared.PageResult[domain.Track]{}, fmt.Errorf("sort %q: %w", f.Sort, shared.ErrInvalidArgument)
	}
	if err := f.Category.Validate(); err != nil {
		return shared.PageResult[domain.Track]{}, err
	}
	f.Query = strings.TrimSpace(f.Query)
	return s.tracks.List(ctx, f, p.Normalize())
}

// CountTracks returns the number of tracks in the catalog.
func (s *Catalog) CountTracks(ctx context.Context) (int, error) {
	return s.tracks.Count(ctx)
}

func (s *Catalog) GetTrack(ctx context.Context, id domain.TrackID) (domain.Track, error) {
	return s.tracks.Get(ctx, id)
}

func (s *Catalog) ListArtists(ctx context.Context, query string, cat domain.CategoryFilter, p shared.Page) (shared.PageResult[domain.Artist], error) {
	if err := cat.Validate(); err != nil {
		return shared.PageResult[domain.Artist]{}, err
	}
	return s.tracks.ListArtists(ctx, strings.TrimSpace(query), cat, p.Normalize())
}

func (s *Catalog) GetArtist(ctx context.Context, id domain.ArtistID) (domain.Artist, error) {
	return s.tracks.GetArtist(ctx, id)
}

func (s *Catalog) ListAlbums(ctx context.Context, artist domain.ArtistID, query string, cat domain.CategoryFilter, p shared.Page) (shared.PageResult[domain.Album], error) {
	if err := cat.Validate(); err != nil {
		return shared.PageResult[domain.Album]{}, err
	}
	return s.tracks.ListAlbums(ctx, artist, strings.TrimSpace(query), cat, p.Normalize())
}

// Categories returns the type/region folders that contain tracks, in
// display order.
func (s *Catalog) Categories(ctx context.Context) ([]domain.CategoryCount, error) {
	return s.tracks.CategoryCounts(ctx)
}

func (s *Catalog) GetAlbum(ctx context.Context, id domain.AlbumID) (domain.Album, []domain.Track, error) {
	album, err := s.tracks.GetAlbum(ctx, id)
	if err != nil {
		return domain.Album{}, nil, err
	}
	res, err := s.tracks.List(ctx, domain.TrackFilter{AlbumID: id, Sort: domain.SortByAlbum}, shared.Page{Limit: shared.MaxPageSize})
	if err != nil {
		return domain.Album{}, nil, err
	}
	return album, res.Items, nil
}

// SearchResult groups the best matches of each kind.
type SearchResult struct {
	Tracks  []domain.Track
	Artists []domain.Artist
	Albums  []domain.Album
}

// Search runs one query against tracks, artists and albums.
func (s *Catalog) Search(ctx context.Context, query string, limit int) (SearchResult, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return SearchResult{}, fmt.Errorf("empty query: %w", shared.ErrInvalidArgument)
	}
	if limit <= 0 || limit > 50 {
		limit = 20
	}
	page := shared.Page{Limit: limit}
	tracks, err := s.tracks.List(ctx, domain.TrackFilter{Query: query, Sort: domain.SortByTitle}, page)
	if err != nil {
		return SearchResult{}, err
	}
	artists, err := s.tracks.ListArtists(ctx, query, domain.CategoryFilter{}, page)
	if err != nil {
		return SearchResult{}, err
	}
	albums, err := s.tracks.ListAlbums(ctx, "", query, domain.CategoryFilter{}, page)
	if err != nil {
		return SearchResult{}, err
	}
	return SearchResult{Tracks: tracks.Items, Artists: artists.Items, Albums: albums.Items}, nil
}

// UpdateMetadata applies a manual correction. User-edited tracks are never
// overwritten by later scans.
func (s *Catalog) UpdateMetadata(ctx context.Context, id domain.TrackID, patch domain.MetadataPatch) (domain.Track, error) {
	t, err := s.tracks.Get(ctx, id)
	if err != nil {
		return domain.Track{}, err
	}
	if err := patch.Apply(&t); err != nil {
		return domain.Track{}, err
	}
	return s.tracks.Upsert(ctx, t)
}

// FindDuplicates reports duplicate groups over the whole catalog.
func (s *Catalog) FindDuplicates(ctx context.Context) ([]domain.DuplicateGroup, error) {
	all, err := s.tracks.All(ctx)
	if err != nil {
		return nil, err
	}
	return domain.FindDuplicates(all), nil
}
