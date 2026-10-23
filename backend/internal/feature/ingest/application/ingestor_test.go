package application_test

import (
	"context"
	"io"
	"log/slog"
	"musicstream/internal/feature/ingest/application"
	"musicstream/internal/feature/ingest/domain"
	"musicstream/internal/feature/ingest/infrastructure"
	library "musicstream/internal/feature/library/domain"
	libraryinfra "musicstream/internal/feature/library/infrastructure"
	"musicstream/internal/platform/audiotag"
	"musicstream/internal/platform/sqlitex"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func write(t *testing.T, path, content string, age time.Duration) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-age)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
}

type countingRefresher struct{ calls int }

func (r *countingRefresher) Refresh(context.Context) error { r.calls++; return nil }

func exists(path string) bool { _, err := os.Stat(path); return err == nil }

func TestIngestIntoExistingLibrary(t *testing.T) {
	ctx := context.Background()
	root := t.TempDir()
	lib, inbox := filepath.Join(root, "library"), filepath.Join(root, "inbox")

	// Existing library: an upper-case artist folder and a known track.
	instJP, songCN := filepath.Join(lib, "純音樂", "日本"), filepath.Join(lib, "歌曲", "華語")
	write(t, filepath.Join(instJP, "CLANNAD", "TOE.mp3"), "existing-toe", time.Hour)
	write(t, filepath.Join(songCN, "那英", "出賣.mp3"), "existing-chumai", time.Hour)

	db, err := sqlitex.Open(ctx, filepath.Join(root, "db.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := libraryinfra.Migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	tracks := libraryinfra.NewTrackRepository(db)
	fs := infrastructure.NewLocalFS()
	hash, _ := fs.Hash(filepath.Join(songCN, "那英", "出賣.mp3"))
	for _, tr := range []library.Track{
		{Path: "純音樂/日本/CLANNAD/TOE.mp3", Title: "TOE", Artist: "CLANNAD"},
		{Path: "歌曲/華語/那英/出賣.mp3", Title: "出賣", Artist: "那英", ContentHash: hash},
	} {
		tr.Size, tr.ModTime, tr.Format = 1, time.Unix(1, 0), library.FormatMP3
		if _, err := tracks.Upsert(ctx, tr); err != nil {
			t.Fatal(err)
		}
	}

	settled := 2 * time.Minute
	write(t, filepath.Join(inbox, "dl", "clannad - Ana.mp3"), "ana", settled)             // folder reused case-insensitively
	write(t, filepath.Join(inbox, "Yiruma - Kiss the Rain (piano).mp3"), "rain", settled) // new artist, guessed category
	write(t, filepath.Join(inbox, "征服 - 那英 na ying.mp3"), "zhengfu", settled)             // known artist on the right
	write(t, filepath.Join(inbox, "copy of 出賣.mp3"), "existing-chumai", settled)          // already in library
	write(t, filepath.Join(inbox, "downloading.mp3.part"), "partial", settled)            // never touched
	write(t, filepath.Join(inbox, "張學友 - 吻別.mp3"), "still copying", 0)                    // too new

	refresher := &countingRefresher{}
	ing := application.NewIngestor(fs, audiotag.NewReader(), tracks, refresher, domain.Layout{
		SourceRoot: inbox, LibraryRoot: lib,
		MiscRoot: filepath.Join(root, "_misc"), DuplicatesRoot: filepath.Join(root, "_duplicates"),
	}, time.Minute, func(time.Time) (application.MoveLog, string, error) {
		p := filepath.Join(root, "undo.jsonl")
		l, err := infrastructure.CreateMoveLog(p)
		return l, p, err
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))

	res, err := ing.Run(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if res.Audio != 3 || res.Duplicates != 1 || res.Failed != 0 {
		t.Fatalf("result: %+v", res)
	}
	if refresher.calls != 1 {
		t.Fatalf("catalog should be refreshed once after files moved, got %d", refresher.calls)
	}
	for _, want := range []string{
		filepath.Join(instJP, "CLANNAD", "Ana.mp3"),
		filepath.Join(songCN, "那英", "征服.mp3"),
		filepath.Join(lib, "純音樂", "歐美", "Yiruma", "Kiss the Rain (piano).mp3"),
		filepath.Join(root, "_duplicates", "copy of 出賣.mp3"),
		filepath.Join(inbox, "downloading.mp3.part"),
		filepath.Join(inbox, "張學友 - 吻別.mp3"),
	} {
		if !exists(want) {
			t.Errorf("missing %s", want)
		}
	}
	if exists(filepath.Join(instJP, "clannad")) {
		t.Error("created a second, lower-case artist folder")
	}
	if exists(filepath.Join(inbox, "dl")) || !exists(inbox) {
		t.Error("empty sub-folders should be removed, the inbox itself kept")
	}

	// Second run is a no-op until the young file settles.
	if res, _ = ing.Run(ctx); res.Audio != 0 {
		t.Fatalf("second run: %+v", res)
	}
	if refresher.calls != 1 {
		t.Fatalf("no-op run must not refresh the catalog, got %d calls", refresher.calls)
	}
}
