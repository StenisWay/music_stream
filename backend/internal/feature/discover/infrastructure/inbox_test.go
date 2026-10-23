package infrastructure

import (
	"os"
	"path/filepath"
	"testing"
)

func TestInboxDeliverKeepsExistingFiles(t *testing.T) {
	work, inbox := t.TempDir(), t.TempDir()
	if err := os.WriteFile(filepath.Join(inbox, "A - B.mp3"), []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(work, "tagged.mp3")
	if err := os.WriteFile(src, []byte("new"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := NewInboxDir(inbox).Deliver(src, "A - B.mp3"); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join(inbox, "A - B (2).mp3"))
	if err != nil || string(got) != "new" {
		t.Fatalf("delivered file: %q, %v", got, err)
	}
	if old, _ := os.ReadFile(filepath.Join(inbox, "A - B.mp3")); string(old) != "old" {
		t.Fatal("existing file was overwritten")
	}
	entries, _ := os.ReadDir(inbox)
	if len(entries) != 2 {
		t.Fatalf("temp file left behind: %v", entries)
	}
}

func TestCandidateFromSearchEntry(t *testing.T) {
	e := entry{
		URL: "https://www.youtube.com/watch?v=DYptgVvkVLQ", Title: "周杰倫 Jay Chou【晴天 Sunny Day】-Official Music Video",
		Channel: "周杰倫 Jay Chou", Duration: 319,
	}
	c, ok := e.candidate("youtube")
	if !ok || c.Uploader != "周杰倫 Jay Chou" || c.Duration.Seconds() != 319 {
		t.Fatalf("candidate: %+v, %v", c, ok)
	}
	if _, ok := (entry{URL: "https://www.youtube.com/channel/x", Title: "live", LiveStatus: "is_live"}).candidate("youtube"); ok {
		t.Error("live streams must be skipped")
	}
	if _, ok := (entry{URL: "https://evil.example/x", Title: "x"}).candidate("youtube"); ok {
		t.Error("foreign URLs must be skipped")
	}
}

func TestSoundCloudSiteMetadataIgnored(t *testing.T) {
	e := entry{WebpageURL: "https://soundcloud.com/a/b", Title: "Song - Singer", Uploader: "Olise", Artist: "S", Track: "Song - Singer"}
	c, ok := e.candidate("soundcloud")
	if !ok || c.SiteArtist != "" || c.SiteTrack != "" {
		t.Fatalf("candidate: %+v, %v", c, ok)
	}
}
