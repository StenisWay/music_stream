// Package domain holds the discover model: songs found on online sources
// (YouTube, SoundCloud) and the download jobs that bring them into the
// library through the inbox.
package domain

import (
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strings"
	"time"

	"musicstream/internal/shared"
	"musicstream/internal/shared/trackmeta"
)

var (
	// ErrUnavailable means the downloader (yt-dlp) is not installed or no
	// inbox is configured.
	ErrUnavailable = errors.New("online sources unavailable")
	// ErrUpstream means the online source failed (network, site changes).
	ErrUpstream = errors.New("online source failed")
)

// Source is an online site that can be searched and downloaded from.
type Source string

const (
	SourceYouTube    Source = "youtube"
	SourceSoundCloud Source = "soundcloud"
)

// hosts lists the hosts each source may be downloaded from. Anything else is
// rejected so the downloader never fetches arbitrary or internal URLs.
var hosts = map[Source][]string{
	SourceYouTube:    {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"},
	SourceSoundCloud: {"soundcloud.com", "www.soundcloud.com", "m.soundcloud.com"},
}

// ParseSource validates a source name; empty means YouTube.
func ParseSource(s string) (Source, error) {
	if s == "" {
		return SourceYouTube, nil
	}
	if _, ok := hosts[Source(s)]; !ok {
		return "", fmt.Errorf("%w: unknown source %q", shared.ErrInvalidArgument, s)
	}
	return Source(s), nil
}

// SourceOfURL returns the source a download URL belongs to.
func SourceOfURL(raw string) (Source, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Port() != "" {
		return "", fmt.Errorf("%w: url must be a plain https link", shared.ErrInvalidArgument)
	}
	host := strings.ToLower(u.Hostname())
	for src, hs := range hosts {
		for _, h := range hs {
			if host == h {
				return src, nil
			}
		}
	}
	return "", fmt.Errorf("%w: unsupported site %q", shared.ErrInvalidArgument, host)
}

// Candidate is one search result on an online source.
type Candidate struct {
	Source    Source
	URL       string
	Title     string // as shown on the site
	Uploader  string
	Duration  time.Duration
	Thumbnail string
	// SiteArtist and SiteTrack are explicit song metadata from the site
	// (YouTube Music), often empty.
	SiteArtist, SiteTrack string
	// Guess is the artist/title the song would be filed under; the user can
	// change it before downloading.
	Guess TrackInfo
}

// TrackInfo is the metadata written into a downloaded file.
type TrackInfo struct {
	Artist string
	Title  string
	Album  string
}

// Normalize trims the fields and requires a title.
func (t TrackInfo) Normalize() (TrackInfo, error) {
	t = TrackInfo{Artist: strings.TrimSpace(t.Artist), Title: strings.TrimSpace(t.Title), Album: strings.TrimSpace(t.Album)}
	if t.Title == "" {
		return t, fmt.Errorf("%w: title is required", shared.ErrInvalidArgument)
	}
	if len(t.Artist) > 200 || len(t.Title) > 300 || len(t.Album) > 300 {
		return t, fmt.Errorf("%w: field too long", shared.ErrInvalidArgument)
	}
	return t, nil
}

// FileName is the inbox file name; ingest moves it to <Artist>/<Album>/<Title>.
func (t TrackInfo) FileName(ext string) string {
	name := t.Title
	if t.Artist != "" {
		name = t.Artist + " - " + t.Title
	}
	return trackmeta.SanitizeSegment(name) + ext
}

// uploaderNoise is what channels append to an artist name.
var uploaderNoise = regexp.MustCompile(`(?i)\s*(-\s*topic|vevo|official(\s*(channel|youtube))?|官方(頻道)?)\s*$`)

// GuessTrack derives artist and title from a candidate's video title,
// falling back to the channel name as artist ("周杰倫 - Topic" uploads carry
// only the title). Explicit site metadata wins. known (may be nil) holds the
// library's artists and decides which side of "A - B" is the artist.
func GuessTrack(c Candidate, known trackmeta.ArtistIndex) TrackInfo {
	if c.SiteArtist != "" && c.SiteTrack != "" {
		return TrackInfo{Artist: strings.TrimSpace(c.SiteArtist), Title: strings.TrimSpace(c.SiteTrack)}
	}
	hint := strings.TrimSpace(uploaderNoise.ReplaceAllString(c.Uploader, ""))
	title := c.Title
	// "Title - Artist | Remix 女聲版 | lyrics…": what follows a bar is decoration.
	if head, _, ok := strings.Cut(strings.ReplaceAll(title, "｜", "|"), "|"); ok && strings.Contains(head, " - ") {
		title = head
	}
	// The ".x" suffix stops the parser from treating "Mr. Brown" as an extension.
	m := trackmeta.ParseFilename(title+".x", hint, known)
	if m.Title == "" {
		m.Title = strings.TrimSpace(c.Title)
	}
	return TrackInfo{Artist: m.Artist, Title: m.Title}
}

// JobID identifies a download job.
type JobID int64

// Status is the progress of a download job.
type Status string

const (
	StatusQueued      Status = "queued"
	StatusDownloading Status = "downloading"
	// StatusDone means the file is in the inbox; ingest files it into the
	// library within seconds.
	StatusDone   Status = "done"
	StatusFailed Status = "failed"
)

// Job is a request to download one song into the library.
type Job struct {
	ID        JobID
	URL       string
	Info      TrackInfo
	Status    Status
	Error     string // user-facing reason when failed
	CreatedAt time.Time
	UpdatedAt time.Time
}

// Active reports whether the job is still waiting or running.
func (j Job) Active() bool { return j.Status == StatusQueued || j.Status == StatusDownloading }
