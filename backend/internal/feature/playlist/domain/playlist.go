// Package domain holds the playlist entities and rules: user-owned ordered
// playlists and favorites. Tracks are referenced by library track id only.
package domain

import (
	"fmt"
	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/shared"
	"strings"
	"time"
	"unicode/utf8"
)

type PlaylistID int64

// Playlist is an ordered, user-owned list of tracks. A track may appear more
// than once.
type Playlist struct {
	ID        PlaylistID
	OwnerID   shared.UserID
	Name      string
	TrackIDs  []library.TrackID
	CreatedAt time.Time
	UpdatedAt time.Time
}

const (
	MaxPlaylistNameLen = 100
	MaxPlaylistTracks  = 5000
)

// ValidatePlaylistName trims and checks a playlist name.
func ValidatePlaylistName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" || utf8.RuneCountInString(name) > MaxPlaylistNameLen {
		return "", shared.ErrInvalidArgument
	}
	return name, nil
}

// PlaylistSummary is used in listings.
type PlaylistSummary struct {
	ID         PlaylistID
	Name       string
	TrackCount int
	UpdatedAt  time.Time
}

// ValidateTrackCount rejects playlists longer than MaxPlaylistTracks.
func ValidateTrackCount(ids []library.TrackID) error {
	if len(ids) > MaxPlaylistTracks {
		return fmt.Errorf("too many tracks: %w", shared.ErrInvalidArgument)
	}
	return nil
}

// UniqueTrackIDs returns ids without repeats, in first-seen order.
func UniqueTrackIDs(ids []library.TrackID) []library.TrackID {
	seen := make(map[library.TrackID]bool, len(ids))
	out := make([]library.TrackID, 0, len(ids))
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}
