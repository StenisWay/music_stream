// Package application holds the playlist use cases. Tracks are resolved
// through the TrackCatalog port, so this feature never depends on how the
// library stores them.
package application

import (
	"context"
	"fmt"

	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/feature/playlist/domain"
	"musicstream/internal/shared"
)

// TrackCatalog is what playlists need from the library.
type TrackCatalog interface {
	Get(ctx context.Context, id library.TrackID) (library.Track, error)
	// ListByIDs returns the tracks that exist among ids, in any order.
	ListByIDs(ctx context.Context, ids []library.TrackID) ([]library.Track, error)
}

type Service struct {
	playlists domain.Repository
	tracks    TrackCatalog
}

func NewService(playlists domain.Repository, tracks TrackCatalog) *Service {
	return &Service{playlists: playlists, tracks: tracks}
}

func (s *Service) List(ctx context.Context, owner shared.UserID) ([]domain.PlaylistSummary, error) {
	return s.playlists.List(ctx, owner)
}

// Get returns the playlist with its tracks resolved in order. Tracks that
// were removed from the library are skipped.
func (s *Service) Get(ctx context.Context, owner shared.UserID, id domain.PlaylistID) (domain.Playlist, []library.Track, error) {
	p, err := s.playlists.Get(ctx, owner, id)
	if err != nil {
		return domain.Playlist{}, nil, err
	}
	ordered, err := s.orderedTracks(ctx, p.TrackIDs)
	return p, ordered, err
}

func (s *Service) Create(ctx context.Context, owner shared.UserID, name string, trackIDs []library.TrackID) (domain.Playlist, error) {
	name, err := domain.ValidatePlaylistName(name)
	if err != nil {
		return domain.Playlist{}, err
	}
	if err := s.validateTracks(ctx, trackIDs); err != nil {
		return domain.Playlist{}, err
	}
	return s.playlists.Create(ctx, domain.Playlist{OwnerID: owner, Name: name, TrackIDs: trackIDs})
}

func (s *Service) Rename(ctx context.Context, owner shared.UserID, id domain.PlaylistID, name string) error {
	name, err := domain.ValidatePlaylistName(name)
	if err != nil {
		return err
	}
	return s.playlists.Rename(ctx, owner, id, name)
}

// ReplaceTracks sets the full ordered track list (used for reorder/remove).
func (s *Service) ReplaceTracks(ctx context.Context, owner shared.UserID, id domain.PlaylistID, trackIDs []library.TrackID) error {
	if err := s.validateTracks(ctx, trackIDs); err != nil {
		return err
	}
	return s.playlists.SetTracks(ctx, owner, id, trackIDs)
}

// AppendTracks adds tracks at the end.
func (s *Service) AppendTracks(ctx context.Context, owner shared.UserID, id domain.PlaylistID, trackIDs []library.TrackID) error {
	p, err := s.playlists.Get(ctx, owner, id)
	if err != nil {
		return err
	}
	if err := s.validateTracks(ctx, trackIDs); err != nil {
		return err
	}
	return s.playlists.SetTracks(ctx, owner, id, append(p.TrackIDs, trackIDs...))
}

func (s *Service) Delete(ctx context.Context, owner shared.UserID, id domain.PlaylistID) error {
	return s.playlists.Delete(ctx, owner, id)
}

func (s *Service) AddFavorite(ctx context.Context, owner shared.UserID, track library.TrackID) error {
	if _, err := s.tracks.Get(ctx, track); err != nil {
		return err
	}
	return s.playlists.AddFavorite(ctx, owner, track)
}

func (s *Service) RemoveFavorite(ctx context.Context, owner shared.UserID, track library.TrackID) error {
	return s.playlists.RemoveFavorite(ctx, owner, track)
}

func (s *Service) ListFavorites(ctx context.Context, owner shared.UserID, p shared.Page) (shared.PageResult[library.Track], error) {
	ids, err := s.playlists.ListFavorites(ctx, owner, p.Normalize())
	if err != nil {
		return shared.PageResult[library.Track]{}, err
	}
	tracks, err := s.orderedTracks(ctx, ids.Items)
	return shared.PageResult[library.Track]{Items: tracks, Total: ids.Total, Page: ids.Page}, err
}

// orderedTracks loads tracks keeping the order (and repeats) of ids and
// skipping ids that are no longer in the library.
func (s *Service) orderedTracks(ctx context.Context, ids []library.TrackID) ([]library.Track, error) {
	found, err := s.tracks.ListByIDs(ctx, ids)
	if err != nil {
		return nil, err
	}
	byID := make(map[library.TrackID]library.Track, len(found))
	for _, t := range found {
		byID[t.ID] = t
	}
	out := make([]library.Track, 0, len(ids))
	for _, id := range ids {
		if t, ok := byID[id]; ok {
			out = append(out, t)
		}
	}
	return out, nil
}

// FavoriteFlags tells which of the given tracks the user has favorited.
func (s *Service) FavoriteFlags(ctx context.Context, owner shared.UserID, ids []library.TrackID) (map[library.TrackID]bool, error) {
	if len(ids) == 0 {
		return map[library.TrackID]bool{}, nil
	}
	return s.playlists.FavoriteIDs(ctx, owner, ids)
}

// validateTracks enforces the length limit and that every id exists.
func (s *Service) validateTracks(ctx context.Context, ids []library.TrackID) error {
	if err := domain.ValidateTrackCount(ids); err != nil {
		return err
	}
	unique := domain.UniqueTrackIDs(ids)
	found, err := s.tracks.ListByIDs(ctx, unique)
	if err != nil {
		return err
	}
	if len(found) != len(unique) {
		return fmt.Errorf("unknown track id: %w", shared.ErrInvalidArgument)
	}
	return nil
}
