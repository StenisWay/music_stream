package infrastructure_test

import (
	"context"
	"errors"
	"fmt"
	"musicstream/internal/feature/library/domain"
	"musicstream/internal/feature/library/infrastructure"
	"musicstream/internal/platform/sqlitex"
	"musicstream/internal/shared"
	"path/filepath"
	"strings"
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

	artists, err := tracks.ListArtists(ctx, "", domain.CategoryFilter{}, shared.Page{Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	if artists.Total != 2 {
		t.Fatalf("artists should group case-insensitively, got %d", artists.Total)
	}

	albums, err := tracks.ListAlbums(ctx, "", "", domain.CategoryFilter{}, shared.Page{Limit: 10})
	if err != nil || albums.Total != 1 || albums.Items[0].TrackCount != 2 {
		t.Fatalf("albums: %+v %v", albums, err)
	}
	if _, err := tracks.GetAlbum(ctx, "missing"); !errors.Is(err, shared.ErrNotFound) {
		t.Fatalf("missing album: %v", err)
	}
}

func TestCategoryFilter(t *testing.T) {
	tracks := infrastructure.NewTrackRepository(openTest(t))
	ctx := context.Background()
	seed(t, tracks,
		domain.Track{Path: "歌曲/華語/梁靜茹/勇氣.mp3", Title: "勇氣", Artist: "梁靜茹"},
		domain.Track{Path: "歌曲/華語/五月天/倔強.mp3", Title: "倔強", Artist: "五月天"},
		domain.Track{Path: "歌曲/日本/片霧烈火/約束.mp3", Title: "約束", Artist: "片霧烈火", Album: "片霧烈火"},
		domain.Track{Path: "純音樂/日本/片霧烈火/片霧烈火/約束 (off vocal).mp3", Title: "約束 (off vocal)", Artist: "片霧烈火", Album: "片霧烈火"},
		domain.Track{Path: "純音樂/韓國/Yiruma/River.mp3", Title: "River", Artist: "Yiruma"},
		domain.Track{Path: "舊資料夾/x.mp3", Title: "x", Artist: "X"}, // outside the layout
	)
	page := shared.Page{Limit: 10}
	count := func(f domain.CategoryFilter) int {
		res, err := tracks.List(ctx, domain.TrackFilter{Category: f, Sort: domain.SortByTitle}, page)
		if err != nil {
			t.Fatal(err)
		}
		return res.Total
	}
	for f, want := range map[domain.CategoryFilter]int{
		{}:                          6,
		{Type: "歌曲"}:                3,
		{Type: "純音樂"}:               2,
		{Region: "日本"}:              2,
		{Type: "純音樂", Region: "日本"}: 1,
		{Type: "歌曲", Region: "韓國"}:  0,
	} {
		if got := count(f); got != want {
			t.Errorf("%+v: got %d want %d", f, got, want)
		}
	}

	// An artist in two folders is listed in each, counting only that folder.
	artists, _ := tracks.ListArtists(ctx, "", domain.CategoryFilter{Type: "純音樂"}, page)
	if artists.Total != 2 {
		t.Fatalf("instrumental artists: %+v", artists)
	}
	albums, _ := tracks.ListAlbums(ctx, "", "", domain.CategoryFilter{Type: "歌曲"}, page)
	if albums.Total != 1 || albums.Items[0].TrackCount != 1 {
		t.Fatalf("song albums: %+v", albums)
	}

	counts, err := tracks.CategoryCounts(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, c := range counts {
		got = append(got, fmt.Sprintf("%s:%d/%d", c.Category.Dir(), c.TrackCount, c.ArtistCount))
	}
	if want := "歌曲/華語:2/2 歌曲/日本:1/1 純音樂/日本:1/1 純音樂/韓國:1/1"; strings.Join(got, " ") != want {
		t.Fatalf("counts: got %v want %s", got, want)
	}
}
