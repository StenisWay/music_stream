package domain

import (
	"context"

	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/shared"
)

// Repository persists playlists and favorites.
type Repository interface {
	List(ctx context.Context, owner shared.UserID) ([]PlaylistSummary, error)
	Get(ctx context.Context, owner shared.UserID, id PlaylistID) (Playlist, error)
	Create(ctx context.Context, p Playlist) (Playlist, error)
	Rename(ctx context.Context, owner shared.UserID, id PlaylistID, name string) error
	SetTracks(ctx context.Context, owner shared.UserID, id PlaylistID, tracks []library.TrackID) error
	Delete(ctx context.Context, owner shared.UserID, id PlaylistID) error

	AddFavorite(ctx context.Context, owner shared.UserID, track library.TrackID) error
	RemoveFavorite(ctx context.Context, owner shared.UserID, track library.TrackID) error
	ListFavorites(ctx context.Context, owner shared.UserID, p shared.Page) (shared.PageResult[library.TrackID], error)
	FavoriteIDs(ctx context.Context, owner shared.UserID, among []library.TrackID) (map[library.TrackID]bool, error)
}
