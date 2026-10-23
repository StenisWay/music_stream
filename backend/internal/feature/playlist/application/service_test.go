package application

import (
	"context"
	"errors"
	"testing"

	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/feature/playlist/domain"
	"musicstream/internal/shared"
)

// fakeRepo stores a single playlist; only the methods AppendTracks uses are implemented.
type fakeRepo struct {
	domain.Repository
	p domain.Playlist
}

func (r *fakeRepo) Get(context.Context, shared.UserID, domain.PlaylistID) (domain.Playlist, error) {
	return r.p, nil
}

func (r *fakeRepo) SetTracks(_ context.Context, _ shared.UserID, _ domain.PlaylistID, ids []library.TrackID) error {
	r.p.TrackIDs = ids
	return nil
}

// fakeCatalog reports every requested id as existing.
type fakeCatalog struct{}

func (fakeCatalog) Get(_ context.Context, id library.TrackID) (library.Track, error) {
	return library.Track{ID: id}, nil
}

func (fakeCatalog) ListByIDs(_ context.Context, ids []library.TrackID) ([]library.Track, error) {
	out := make([]library.Track, len(ids))
	for i, id := range ids {
		out[i] = library.Track{ID: id}
	}
	return out, nil
}

func trackIDs(n int) []library.TrackID {
	ids := make([]library.TrackID, n)
	for i := range ids {
		ids[i] = library.TrackID(i + 1)
	}
	return ids
}

func TestAppendTracksEnforcesTotalLimit(t *testing.T) {
	ctx := context.Background()

	repo := &fakeRepo{p: domain.Playlist{ID: 1, TrackIDs: trackIDs(domain.MaxPlaylistTracks)}}
	err := NewService(repo, fakeCatalog{}).AppendTracks(ctx, 1, 1, []library.TrackID{1})
	if !errors.Is(err, shared.ErrInvalidArgument) {
		t.Fatalf("append past limit: err = %v, want ErrInvalidArgument", err)
	}
	if len(repo.p.TrackIDs) != domain.MaxPlaylistTracks {
		t.Fatalf("playlist changed to %d tracks after rejected append", len(repo.p.TrackIDs))
	}

	repo = &fakeRepo{p: domain.Playlist{ID: 1, TrackIDs: trackIDs(domain.MaxPlaylistTracks - 1)}}
	if err := NewService(repo, fakeCatalog{}).AppendTracks(ctx, 1, 1, []library.TrackID{1}); err != nil {
		t.Fatalf("append up to limit: %v", err)
	}
	if len(repo.p.TrackIDs) != domain.MaxPlaylistTracks {
		t.Fatalf("got %d tracks, want %d", len(repo.p.TrackIDs), domain.MaxPlaylistTracks)
	}
}

func TestRemoveTrackDropsEveryOccurrence(t *testing.T) {
	ctx := context.Background()
	repo := &fakeRepo{p: domain.Playlist{ID: 1, TrackIDs: []library.TrackID{2, 1, 2, 3}}}
	if err := NewService(repo, fakeCatalog{}).RemoveTrack(ctx, 1, 1, 2); err != nil {
		t.Fatal(err)
	}
	if got := repo.p.TrackIDs; len(got) != 2 || got[0] != 1 || got[1] != 3 {
		t.Fatalf("got %v, want [1 3]", got)
	}
	if err := NewService(repo, fakeCatalog{}).RemoveTrack(ctx, 1, 1, 9); err != nil {
		t.Fatalf("removing an absent track: %v", err)
	}
}
