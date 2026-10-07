package infrastructure_test

import (
	"context"
	"errors"
	auth "musicstream/internal/feature/auth/domain"
	authinfra "musicstream/internal/feature/auth/infrastructure"
	library "musicstream/internal/feature/library/domain"
	libraryinfra "musicstream/internal/feature/library/infrastructure"
	"musicstream/internal/feature/playlist/domain"
	"musicstream/internal/feature/playlist/infrastructure"
	"musicstream/internal/platform/sqlitex"
	"musicstream/internal/shared"
	"path/filepath"
	"testing"
	"time"
)

func openTest(t *testing.T) *sqlitex.DB {
	t.Helper()
	ctx := context.Background()
	db, err := sqlitex.Open(ctx, filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	for _, m := range []func(context.Context, *sqlitex.DB) error{libraryinfra.Migrate, authinfra.Migrate, infrastructure.Migrate} {
		if err := m(ctx, db); err != nil {
			t.Fatal(err)
		}
	}
	return db
}

func seed(t *testing.T, tracks *libraryinfra.TrackRepository, items ...library.Track) []library.Track {
	t.Helper()
	var out []library.Track
	for _, tr := range items {
		tr.Size, tr.ModTime, tr.Format = 1, time.Unix(1, 0), library.FormatMP3
		saved, err := tracks.Upsert(context.Background(), tr)
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, saved)
	}
	return out
}

func TestPlaylistsAndFavorites(t *testing.T) {
	db := openTest(t)
	tracks := libraryinfra.NewTrackRepository(db)
	ctx := context.Background()
	u, err := authinfra.NewUserRepository(db).Upsert(ctx, auth.User{Username: "admin", PasswordHash: "x", CreatedAt: time.Now()})
	if err != nil {
		t.Fatal(err)
	}
	tr := seed(t, tracks, library.Track{Path: "1.mp3", Title: "a"}, library.Track{Path: "2.mp3", Title: "b"})
	repo := infrastructure.NewPlaylistRepository(db)

	p, err := repo.Create(ctx, domain.Playlist{OwnerID: u.ID, Name: "mix", TrackIDs: []library.TrackID{tr[1].ID, tr[0].ID, tr[1].ID}})
	if err != nil {
		t.Fatal(err)
	}
	got, err := repo.Get(ctx, u.ID, p.ID)
	if err != nil || len(got.TrackIDs) != 3 || got.TrackIDs[0] != tr[1].ID {
		t.Fatalf("order/duplicates not preserved: %+v %v", got, err)
	}
	if _, err := repo.Get(ctx, u.ID+1, p.ID); !errors.Is(err, shared.ErrNotFound) {
		t.Fatalf("other user must not see playlist: %v", err)
	}

	// Deleting a track cascades out of playlists and favorites.
	if err := repo.AddFavorite(ctx, u.ID, tr[0].ID); err != nil {
		t.Fatal(err)
	}
	if err := tracks.Delete(ctx, []library.TrackID{tr[0].ID}); err != nil {
		t.Fatal(err)
	}
	got, _ = repo.Get(ctx, u.ID, p.ID)
	if len(got.TrackIDs) != 2 {
		t.Fatalf("cascade: %+v", got.TrackIDs)
	}
	favs, _ := repo.ListFavorites(ctx, u.ID, shared.Page{Limit: 10})
	if favs.Total != 0 {
		t.Fatalf("favorite should be gone: %d", favs.Total)
	}
}
