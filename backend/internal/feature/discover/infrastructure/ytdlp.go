// Package infrastructure implements the discover ports: yt-dlp for searching
// and downloading, ffmpeg for tagging, and the inbox folder on disk.
package infrastructure

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"musicstream/internal/feature/discover/application"
	"musicstream/internal/feature/discover/domain"
)

var _ application.Fetcher = (*YtDlp)(nil)

// searchPrefix is yt-dlp's search syntax per source ("ytsearch10:query").
var searchPrefix = map[domain.Source]string{
	domain.SourceYouTube:    "ytsearch",
	domain.SourceSoundCloud: "scsearch",
}

// YtDlp implements application.Fetcher with the yt-dlp and ffmpeg binaries.
type YtDlp struct {
	bin, ffmpeg string
	workRoot    string // downloads in progress and yt-dlp's cache
}

// NewYtDlp finds the binaries; Available() is false when either is missing.
func NewYtDlp(bin, ffmpeg, workRoot string) *YtDlp {
	y := &YtDlp{workRoot: workRoot}
	if bin == "" {
		bin = "yt-dlp"
	}
	if ffmpeg == "" {
		ffmpeg = "ffmpeg"
	}
	if p, err := exec.LookPath(bin); err == nil {
		y.bin = p
	}
	if p, err := exec.LookPath(ffmpeg); err == nil {
		y.ffmpeg = p
	}
	return y
}

func (y *YtDlp) Available() bool { return y.bin != "" && y.ffmpeg != "" }

// commonArgs ignore any user config and keep yt-dlp's cache (player JS used
// to unlock YouTube formats) in the work folder.
func (y *YtDlp) commonArgs() []string {
	return []string{"--ignore-config", "--no-warnings", "--socket-timeout", "20",
		"--cache-dir", filepath.Join(y.workRoot, "cache")}
}

// entry is the subset of yt-dlp's JSON we use.
type entry struct {
	ID         string   `json:"id"`
	URL        string   `json:"url"`
	WebpageURL string   `json:"webpage_url"`
	Title      string   `json:"title"`
	Uploader   string   `json:"uploader"`
	Channel    string   `json:"channel"`
	Duration   float64  `json:"duration"`
	Artist     string   `json:"artist"`
	Artists    []string `json:"artists"`
	Track      string   `json:"track"`
	LiveStatus string   `json:"live_status"`
	Thumbnail  string   `json:"thumbnail"`
	Thumbnails []struct {
		URL   string `json:"url"`
		Width int    `json:"width"`
	} `json:"thumbnails"`
}

func (y *YtDlp) Search(ctx context.Context, src domain.Source, query string, limit int) ([]domain.Candidate, error) {
	prefix, ok := searchPrefix[src]
	if !ok {
		return nil, fmt.Errorf("no search for source %q", src)
	}
	args := append(y.commonArgs(), "--flat-playlist", "--dump-json", prefix+strconv.Itoa(limit)+":"+query)
	out, err := y.run(ctx, y.bin, args...)
	if err != nil {
		return nil, err
	}
	var res []domain.Candidate
	sc := bufio.NewScanner(bytes.NewReader(out))
	sc.Buffer(make([]byte, 1<<20), 8<<20)
	for sc.Scan() {
		var e entry
		if json.Unmarshal(sc.Bytes(), &e) != nil {
			continue
		}
		if c, ok := e.candidate(src); ok {
			res = append(res, c)
		}
	}
	return res, sc.Err()
}

func (e entry) candidate(src domain.Source) (domain.Candidate, bool) {
	u := e.WebpageURL
	if u == "" {
		u = e.URL
	}
	if s, err := domain.SourceOfURL(u); err != nil || s != src || e.LiveStatus == "is_live" || e.Title == "" {
		return domain.Candidate{}, false
	}
	uploader := e.Uploader
	if uploader == "" {
		uploader = e.Channel
	}
	c := domain.Candidate{
		Source: src, URL: u, Title: e.Title, Uploader: uploader,
		Duration:  time.Duration(e.Duration * float64(time.Second)),
		Thumbnail: e.thumbnail(),
	}
	// SoundCloud fills artist/track with the uploader and the raw title;
	// only YouTube Music's song metadata is worth trusting.
	if src == domain.SourceYouTube {
		c.SiteArtist, c.SiteTrack = e.Artist, e.Track
		if c.SiteArtist == "" {
			c.SiteArtist = strings.Join(e.Artists, ", ")
		}
	}
	return c, true
}

// thumbnail picks the image closest to 300px wide (list rows are small).
func (e entry) thumbnail() string {
	best, bestDiff := e.Thumbnail, math.MaxInt
	for _, t := range e.Thumbnails {
		if t.URL == "" || !strings.HasPrefix(t.URL, "https://") {
			continue
		}
		diff := 1000 // unknown width: use only if nothing better
		if t.Width > 0 {
			diff = t.Width - 300
			if diff < 0 {
				diff = -diff
			}
		}
		if diff < bestDiff {
			best, bestDiff = t.URL, diff
		}
	}
	return best
}

func (y *YtDlp) Download(ctx context.Context, url string, info domain.TrackInfo) (string, func(), error) {
	if err := os.MkdirAll(y.workRoot, 0o755); err != nil {
		return "", nil, err
	}
	dir, err := os.MkdirTemp(y.workRoot, "dl-")
	if err != nil {
		return "", nil, err
	}
	cleanup := func() { _ = os.RemoveAll(dir) }

	args := append(y.commonArgs(),
		"--no-playlist", "--no-progress",
		// Skip live streams and hour-long mixes; "<=?" accepts unknown durations.
		"--match-filter", "!is_live & duration <=? 3600",
		"-f", "bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", "0",
		"--embed-thumbnail", "--convert-thumbnails", "jpg",
		"--ffmpeg-location", y.ffmpeg,
		"-o", filepath.Join(dir, "audio.%(ext)s"),
		"--print", "after_move:filepath",
		"--", url)
	out, err := y.run(ctx, y.bin, args...)
	if err != nil {
		return "", cleanup, err
	}
	audio := lastLine(out)
	if audio == "" {
		return "", cleanup, errors.New("skipped: live stream or longer than one hour")
	}

	tagged := filepath.Join(dir, "tagged"+filepath.Ext(audio))
	targs := []string{"-hide_banner", "-loglevel", "error", "-nostdin", "-i", audio,
		"-map", "0", "-c", "copy", "-id3v2_version", "3",
		"-metadata", "title=" + info.Title, "-metadata", "artist=" + info.Artist}
	if info.Album != "" {
		targs = append(targs, "-metadata", "album="+info.Album)
	}
	if _, err := y.run(ctx, y.ffmpeg, append(targs, "-y", tagged)...); err != nil {
		return "", cleanup, fmt.Errorf("write tags: %w", err)
	}
	return tagged, cleanup, nil
}

// run executes a binary and returns stdout; the error carries stderr.
func (y *YtDlp) run(ctx context.Context, bin string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, bin, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("%s: %w\n%s", filepath.Base(bin), err, bytes.TrimSpace(stderr.Bytes()))
	}
	return stdout.Bytes(), nil
}

func lastLine(b []byte) string {
	lines := strings.Split(strings.TrimSpace(string(b)), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}
