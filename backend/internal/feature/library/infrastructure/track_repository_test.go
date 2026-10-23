package infrastructure_test

import (
	"context"
	"errors"
	"musicstream/internal/feature/library/domain"
	"musicstream/internal/feature/library/infrastructure"
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
	if err := infrastructure.Migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	return db
}

func seed(t *testing.T, tracks *infrastructure.TrackRepository, items ...domain.Track) []domain.Track {
	t.Helper()
	var out []domain.Track
	for _, tr := range items {
		tr.Size, tr.ModTime, tr.Format = 1, time.Unix(1, 0), domain.FormatMP3
		saved, err := tracks.Upsert(context.Background(), tr)
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, saved)
	}
	return out
}

func TestTrackQueries(t *testing.T) {
	tracks := infrastructure.NewTrackRepository(openTest(t))
	ctx := context.Background()
	seed(t, tracks,
		domain.Track{Path: "a/1.mp3", Title: "情歌", Artist: "梁靜茹", Album: "Fall In Love", TrackNo: 2},
		domain.Track{Path: "a/2.mp3", Title: "勇氣", Artist: "梁靜茹", Album: "Fall In Love", TrackNo: 1},
		domain.Track{Path: "b/1.mp3", Title: "Ana", Artist: "CLANNAD"},
		domain.Track{Path: "b/2.mp3", Title: "Ana", Artist: "clannad"},
	)

	res, err := tracks.List(ctx, domain.TrackFilter{Query: "靜茹", Sort: domain.SortByAlbum}, shared.Page{Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	if res.Total != 2 || res.Items[0].Title != "勇氣" {
		t.Fatalf("search/sort: %+v", res)
	}

	// LIKE wildcards in user input are escaped.
	res, _ = tracks.List(ctx, domain.TrackFilter{Query: "%", Sort: domain.SortByTitle}, shared.Page{Limit: 10})
	if res.Total != 0 {
		t.Fatalf("wildcard not escaped: %d", res.Total)
	}

	artists, err := tracks.ListArtists(ctx, "", shared.Page{Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	if artists.Total != 2 {
		t.Fatalf("artists should group case-insensitively, got %d", artists.Total)
	}

	albums, err := tracks.ListAlbums(ctx, "", "", shared.Page{Limit: 10})
	if err != nil || albums.Total != 1 || albums.Items[0].TrackCount != 2 {
		t.Fatalf("albums: %+v %v", albums, err)
	}
	if _, err := tracks.GetAlbum(ctx, "missing"); !errors.Is(err, shared.ErrNotFound) {
		t.Fatalf("missing album: %v", err)
	}
}
