package domain

import (
	"errors"
	"testing"

	"musicstream/internal/shared"
	"musicstream/internal/shared/trackmeta"
)

func TestGuessTrack(t *testing.T) {
	known := trackmeta.NewArtistSet()
	known.AddTrusted("周杰倫")
	for _, c := range []struct {
		title, uploader, artist, track string
		want                           TrackInfo
	}{
		{"周杰倫 Jay Chou【晴天 Sunny Day】-Official Music Video", "周杰倫 Jay Chou", "", "", TrackInfo{Artist: "周杰倫", Title: "晴天"}},
		{"Adele - Hello (Official Music Video)", "AdeleVEVO", "", "", TrackInfo{Artist: "Adele", Title: "Hello"}},
		{"晴天", "周杰倫 - Topic", "", "", TrackInfo{Artist: "周杰倫", Title: "晴天"}},
		{"anything", "someone", "五月天", "倔強", TrackInfo{Artist: "五月天", Title: "倔強"}},
		{"Mr. Brightside", "The Killers", "", "", TrackInfo{Artist: "The Killers", Title: "Mr. Brightside"}},
		// A library artist on the right-hand side is recognised.
		{"晴天 - 周杰倫 | R&B Ballad Remix", "音樂迷航地圖", "", "", TrackInfo{Artist: "周杰倫", Title: "晴天"}},
	} {
		cand := Candidate{Title: c.title, Uploader: c.uploader, SiteArtist: c.artist, SiteTrack: c.track}
		if got := GuessTrack(cand, known); got != c.want {
			t.Errorf("GuessTrack(%q, %q) = %+v, want %+v", c.title, c.uploader, got, c.want)
		}
	}
}

func TestSourceOfURL(t *testing.T) {
	for raw, want := range map[string]Source{
		"https://www.youtube.com/watch?v=DYptgVvkVLQ":   SourceYouTube,
		"https://youtu.be/DYptgVvkVLQ":                  SourceYouTube,
		"https://music.youtube.com/watch?v=x":           SourceYouTube,
		"https://soundcloud.com/artist/some-track":      SourceSoundCloud,
		"https://M.SoundCloud.com/artist/another-track": SourceSoundCloud,
	} {
		if got, err := SourceOfURL(raw); err != nil || got != want {
			t.Errorf("SourceOfURL(%q) = %q, %v", raw, got, err)
		}
	}
	for _, raw := range []string{
		"http://www.youtube.com/watch?v=x",
		"https://evil.example/watch?v=x",
		"https://youtube.com.evil.example/x",
		"https://user@youtube.com/x",
		"https://youtube.com:8080/x",
		"file:///etc/passwd",
		"--exec=rm",
		"",
	} {
		if _, err := SourceOfURL(raw); !errors.Is(err, shared.ErrInvalidArgument) {
			t.Errorf("SourceOfURL(%q) should be rejected, got %v", raw, err)
		}
	}
}

func TestTrackInfo(t *testing.T) {
	if _, err := (TrackInfo{Artist: "x", Title: "  "}).Normalize(); !errors.Is(err, shared.ErrInvalidArgument) {
		t.Errorf("blank title should be rejected, got %v", err)
	}
	info, err := TrackInfo{Artist: " AC/DC ", Title: " Back In Black? "}.Normalize()
	if err != nil {
		t.Fatal(err)
	}
	if got := info.FileName(".mp3"); got != "AC／DC - Back In Black？.mp3" {
		t.Errorf("FileName = %q", got)
	}
	if got := (TrackInfo{Title: "Untitled"}).FileName(".mp3"); got != "Untitled.mp3" {
		t.Errorf("FileName without artist = %q", got)
	}
}
