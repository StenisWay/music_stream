package application

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"musicstream/internal/feature/discover/domain"
	"musicstream/internal/shared"
	"musicstream/internal/shared/trackmeta"
)

const (
	maxSearchResults = 30
	queueSize        = 100
	// keepFinished bounds the in-memory job history.
	keepFinished = 50
)

// Config tunes the service.
type Config struct {
	SearchTimeout   time.Duration
	DownloadTimeout time.Duration
}

// Service searches online sources and downloads songs one at a time.
// Jobs live in memory: after a restart the history is gone, but finished
// songs are already in the library.
type Service struct {
	fetcher Fetcher
	inbox   Inbox // nil when no inbox is configured
	artists ArtistCatalog
	cfg     Config
	log     *slog.Logger
	now     func() time.Time

	mu     sync.Mutex
	jobs   map[domain.JobID]*domain.Job
	nextID domain.JobID
	queue  chan domain.JobID
}

func NewService(fetcher Fetcher, inbox Inbox, artists ArtistCatalog, cfg Config, log *slog.Logger) *Service {
	if cfg.SearchTimeout <= 0 {
		cfg.SearchTimeout = 30 * time.Second
	}
	if cfg.DownloadTimeout <= 0 {
		cfg.DownloadTimeout = 15 * time.Minute
	}
	return &Service{
		fetcher: fetcher, inbox: inbox, artists: artists, cfg: cfg, log: log, now: time.Now,
		jobs: map[domain.JobID]*domain.Job{}, queue: make(chan domain.JobID, queueSize),
	}
}

// Available reports whether searching and downloading work at all.
func (s *Service) Available() bool { return s.fetcher.Available() && s.inbox != nil }

// Search looks query up on src.
func (s *Service) Search(ctx context.Context, src domain.Source, query string, limit int) ([]domain.Candidate, error) {
	if !s.Available() {
		return nil, domain.ErrUnavailable
	}
	query = strings.TrimSpace(query)
	if query == "" {
		return nil, fmt.Errorf("%w: query is required", shared.ErrInvalidArgument)
	}
	if limit <= 0 || limit > maxSearchResults {
		limit = maxSearchResults
	}
	ctx, cancel := context.WithTimeout(ctx, s.cfg.SearchTimeout)
	defer cancel()
	res, err := s.fetcher.Search(ctx, src, query, limit)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", domain.ErrUpstream, err)
	}
	known := s.knownArtists(ctx)
	for i := range res {
		res[i].Guess = domain.GuessTrack(res[i], known)
	}
	return res, nil
}

// knownArtists indexes the library's artists; guessing still works (less
// well) without them.
func (s *Service) knownArtists(ctx context.Context) *trackmeta.ArtistSet {
	set := trackmeta.NewArtistSet()
	if s.artists == nil {
		return set
	}
	names, err := s.artists.KnownArtists(ctx)
	if err != nil {
		s.log.Warn("load library artists", "err", err)
	}
	for _, n := range names {
		set.AddTrusted(n)
	}
	return set
}

// Enqueue queues a download. Asking again for a song that is still queued
// or downloading returns the existing job.
func (s *Service) Enqueue(url string, info domain.TrackInfo) (domain.Job, error) {
	if !s.Available() {
		return domain.Job{}, domain.ErrUnavailable
	}
	if _, err := domain.SourceOfURL(url); err != nil {
		return domain.Job{}, err
	}
	info, err := info.Normalize()
	if err != nil {
		return domain.Job{}, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	for _, j := range s.jobs {
		if j.URL == url && j.Active() {
			return *j, nil
		}
	}
	now := s.now()
	s.nextID++
	j := &domain.Job{ID: s.nextID, URL: url, Info: info, Status: domain.StatusQueued, CreatedAt: now, UpdatedAt: now}
	select {
	case s.queue <- j.ID:
	default:
		return domain.Job{}, fmt.Errorf("%w: download queue is full", shared.ErrConflict)
	}
	s.jobs[j.ID] = j
	s.prune()
	return *j, nil
}

// Jobs lists download jobs, newest first.
func (s *Service) Jobs() []domain.Job {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]domain.Job, 0, len(s.jobs))
	for _, j := range s.jobs {
		out = append(out, *j)
	}
	sort.Slice(out, func(a, b int) bool { return out[a].ID > out[b].ID })
	return out
}

// Run processes queued downloads until ctx ends.
func (s *Service) Run(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case id := <-s.queue:
			s.process(ctx, id)
		}
	}
}

func (s *Service) process(ctx context.Context, id domain.JobID) {
	j, ok := s.update(id, func(j *domain.Job) { j.Status = domain.StatusDownloading })
	if !ok {
		return
	}
	started := s.now()
	err := s.download(ctx, j)
	if ctx.Err() != nil {
		return // shutting down; the job is lost with the process
	}
	if err != nil {
		s.log.Warn("download failed", "job", id, "url", j.URL, "err", err)
		s.update(id, func(j *domain.Job) { j.Status, j.Error = domain.StatusFailed, userError(err) })
		return
	}
	s.log.Info("downloaded into inbox", "job", id, "artist", j.Info.Artist, "title", j.Info.Title,
		"took", s.now().Sub(started).Round(time.Second))
	s.update(id, func(j *domain.Job) { j.Status = domain.StatusDone })
}

func (s *Service) download(ctx context.Context, j domain.Job) error {
	ctx, cancel := context.WithTimeout(ctx, s.cfg.DownloadTimeout)
	defer cancel()
	file, cleanup, err := s.fetcher.Download(ctx, j.URL, j.Info)
	if cleanup != nil {
		defer cleanup()
	}
	if err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return fmt.Errorf("timed out after %s", s.cfg.DownloadTimeout)
		}
		return err
	}
	return s.inbox.Deliver(file, j.Info.FileName(filepath.Ext(file)))
}

// update changes a job under the lock and returns a copy.
func (s *Service) update(id domain.JobID, fn func(*domain.Job)) (domain.Job, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	j, ok := s.jobs[id]
	if !ok {
		return domain.Job{}, false
	}
	fn(j)
	j.UpdatedAt = s.now()
	return *j, true
}

// prune drops the oldest finished jobs beyond keepFinished. Caller holds mu.
func (s *Service) prune() {
	var finished []domain.JobID
	for id, j := range s.jobs {
		if !j.Active() {
			finished = append(finished, id)
		}
	}
	if len(finished) <= keepFinished {
		return
	}
	sort.Slice(finished, func(a, b int) bool { return finished[a] < finished[b] })
	for _, id := range finished[:len(finished)-keepFinished] {
		delete(s.jobs, id)
	}
}

// userError keeps the last line of a downloader error, which is the reason
// ("ERROR: [youtube] x: Video unavailable"), short enough for the app.
func userError(err error) string {
	msg := strings.TrimSpace(err.Error())
	if i := strings.LastIndex(msg, "\n"); i >= 0 {
		msg = strings.TrimSpace(msg[i+1:])
	}
	if r := []rune(msg); len(r) > 200 {
		msg = string(r[:200]) + "…"
	}
	return msg
}
