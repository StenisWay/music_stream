package domain

import (
	"context"

	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/shared"
)

// Repository persists playlists.
type Repository interface {
	List(ctx context.Context, owner shared.UserID) ([]PlaylistSummary, error)
	Get(ctx context.Context, owner shared.UserID, id PlaylistID) (Playlist, error)
	Create(ctx context.Context, p Playlist) (Playlist, error)
	Rename(ctx context.Context, owner shared.UserID, id PlaylistID, name string) error
	SetTracks(ctx context.Context, owner shared.UserID, id PlaylistID, tracks []library.TrackID) error
	Delete(ctx context.Context, owner shared.UserID, id PlaylistID) error
	// Containing returns the ids of the owner's playlists that include track.
	Containing(ctx context.Context, owner shared.UserID, track library.TrackID) (map[PlaylistID]bool, error)
}
