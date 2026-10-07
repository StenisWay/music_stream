package application_test

import (
	"context"
	"io"
	"log/slog"
	"musicstream/internal/feature/library/application"
	"musicstream/internal/feature/library/domain"
	"musicstream/internal/feature/library/infrastructure"
	"musicstream/internal/platform/sqlitex"
	"path/filepath"
	"testing"
	"time"
)

type fakeSource struct {
	files map[string]application.FileInfo
}

func (f *fakeSource) ListAudioFiles(context.Context) ([]application.FileInfo, error) {
	var out []application.FileInfo
	for _, fi := range f.files {
		out = append(out, fi)
	}
	return out, nil
}
func (f *fakeSource) Stat(rel string) (application.FileInfo, error) { return f.files[rel], nil }
func (f *fakeSource) AbsPath(rel string) (string, error)            { return "/lib/" + rel, nil }
func (f *fakeSource) FindFolderArtwork(string) (string, bool)       { return "", false }
func (f *fakeSource) add(rel string, size int64, mod time.Time) {
	f.files[rel] = application.FileInfo{Path: rel, Size: size, ModTime: mod}
}
func (f *fakeSource) move(from, to string) {
	fi := f.files[from]
	delete(f.files, from)
	fi.Path = to
	f.files[to] = fi
}

type fakeProber struct{}

func (fakeProber) Probe(string) (application.ProbeResult, error) {
	return application.ProbeResult{Duration: time.Minute}, nil
}

// contentHasher hashes by file size so a moved file keeps its "content".
type contentHasher struct{ src *fakeSource }

func (h contentHasher) Hash(abs string) (string, error) {
	rel, _ := filepath.Rel("/lib", abs)
	return "h" + time.Duration(h.src.files[rel].Size).String(), nil
}

func TestScannerIncrementalMoveAndCuration(t *testing.T) {
	ctx := context.Background()
	db, err := sqlitex.Open(ctx, filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := infrastructure.Migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	store := infrastructure.NewTrackRepository(db)

	src := &fakeSource{files: map[string]application.FileInfo{}}
	t0 := time.Unix(1000, 0)
	src.add("梁靜茹/勇氣.mp3", 100, t0)
	src.add("梁靜茹/情歌.mp3", 200, t0)
	s := application.NewScanner(src, fakeProber{}, contentHasher{src}, store, slog.New(slog.NewTextHandler(io.Discard, nil)))

	res, err := s.FullScan(ctx)
	if err != nil || res.Added != 2 {
		t.Fatalf("first scan: %+v %v", res, err)
	}
	if res, _ = s.FullScan(ctx); res.Unchanged != 2 || res.Added+res.Updated != 0 {
		t.Fatalf("rescan should be a no-op: %+v", res)
	}

	// User fixes metadata, then the file is moved: ID and curation survive.
	all, _ := store.All(ctx)
	var courage domain.Track
	for _, tr := range all {
		if tr.Title == "勇氣" {
			courage = tr
		}
	}
	title := "勇氣 (Live)"
	if err := (domain.MetadataPatch{Title: &title}).Apply(&courage); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Upsert(ctx, courage); err != nil {
		t.Fatal(err)
	}
	src.move("梁靜茹/勇氣.mp3", "梁靜茹/Live/勇氣.mp3")
	res, err = s.FullScan(ctx)
	if err != nil || res.Moved != 1 || res.Removed != 0 {
		t.Fatalf("move: %+v %v", res, err)
	}
	moved, err := store.Get(ctx, courage.ID)
	if err != nil || moved.Path != "梁靜茹/Live/勇氣.mp3" || moved.Title != "勇氣 (Live)" {
		t.Fatalf("moved track: %+v %v", moved, err)
	}

	delete(src.files, "梁靜茹/情歌.mp3")
	if res, _ = s.FullScan(ctx); res.Removed != 1 {
		t.Fatalf("removal: %+v", res)
	}
}
