package application

import (
	"context"
	"fmt"
	"log/slog"
	"musicstream/internal/feature/library/domain"
	"musicstream/internal/shared"
	"musicstream/internal/shared/trackmeta"
	"path"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

// Status is a snapshot of the scanner state.
type Status struct {
	Running    bool
	LastScanAt time.Time
	LastResult ScanResult
}

// ScanResult summarises one scan.
type ScanResult struct {
	Added     int
	Updated   int
	Moved     int
	Removed   int
	Unchanged int
	Failed    int
	Took      time.Duration
}

// Scanner reconciles the catalog with the files on disk. Only new or
// changed files (size/mtime) are read, so re-scans of a large library are
// fast. It is safe for concurrent use; overlapping scans are coalesced.
type Scanner struct {
	src     MediaSource
	prober  Prober
	hasher  Hasher
	tracks  domain.TrackRepository
	log     *slog.Logger
	now     func() time.Time
	workers int

	mu      sync.Mutex // serialises scans
	running atomic.Bool
	status  atomic.Pointer[Status]
}

func NewScanner(src MediaSource, prober Prober, hasher Hasher, tracks domain.TrackRepository, log *slog.Logger) *Scanner {
	s := &Scanner{
		src: src, prober: prober, hasher: hasher, tracks: tracks, log: log,
		now: time.Now, workers: max(2, runtime.NumCPU()/2),
	}
	s.status.Store(&Status{})
	return s
}

func (s *Scanner) Status() Status {
	st := *s.status.Load()
	st.Running = s.running.Load()
	return st
}

// ErrScanInProgress is returned by TryFullScan when a scan is running.
var ErrScanInProgress = fmt.Errorf("scan already in progress: %w", shared.ErrConflict)

// TryFullScan starts a full scan unless one is already running.
func (s *Scanner) TryFullScan(ctx context.Context) (ScanResult, error) {
	if !s.mu.TryLock() {
		return ScanResult{}, ErrScanInProgress
	}
	defer s.mu.Unlock()
	return s.fullScanLocked(ctx)
}

// FullScan waits for any running scan and then scans everything.
func (s *Scanner) FullScan(ctx context.Context) (ScanResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.fullScanLocked(ctx)
}

// Refresh brings the catalog up to date with the music folder, waiting for
// a running scan first.
func (s *Scanner) Refresh(ctx context.Context) error {
	_, err := s.FullScan(ctx)
	return err
}

type job struct {
	file     FileInfo
	existing *domain.Track
}

func (s *Scanner) fullScanLocked(ctx context.Context) (ScanResult, error) {
	s.running.Store(true)
	defer s.running.Store(false)
	started := s.now()

	files, err := s.src.ListAudioFiles(ctx)
	if err != nil {
		return ScanResult{}, fmt.Errorf("list audio files: %w", err)
	}
	existing, err := s.tracks.All(ctx)
	if err != nil {
		return ScanResult{}, fmt.Errorf("load catalog: %w", err)
	}

	byPath := make(map[string]domain.Track, len(existing))
	artists := trackmeta.NewArtistSet()
	for _, t := range existing {
		byPath[t.Path] = t
		artists.AddTrusted(t.Artist)
		artists.AddTrusted(t.AlbumArtist)
	}

	var res ScanResult
	var jobs []job
	seen := make(map[string]bool, len(files))
	for _, f := range files {
		seen[f.Path] = true
		if t, ok := byPath[f.Path]; ok {
			if t.Size == f.Size && t.ModTime.Equal(f.ModTime) {
				res.Unchanged++
				continue
			}
			jobs = append(jobs, job{file: f, existing: &t})
			continue
		}
		jobs = append(jobs, job{file: f})
	}

	// Tracks whose file disappeared: candidates for move detection.
	missingByHash := map[string]domain.Track{}
	var missing []domain.Track
	for _, t := range existing {
		if !seen[t.Path] {
			missing = append(missing, t)
			if t.ContentHash != "" {
				missingByHash[t.ContentHash] = t
			}
		}
	}

	probed := s.probeAll(ctx, jobs, &res)
	if err := ctx.Err(); err != nil {
		return res, err
	}

	// Filename-derived artist candidates of the new batch improve parsing.
	for _, p := range probed {
		artists.AddTrusted(p.result.Tags.Artist)
		hints := trackmeta.HintsFromPath(p.job.file.Path)
		artists.AddTrusted(hints.Artist)
		artists.AddCandidate(trackmeta.ParseFilename(path.Base(p.job.file.Path), hints.Artist, nil).Artist)
	}

	reattached := map[domain.TrackID]bool{}
	for _, p := range probed {
		t := s.buildTrack(p, artists)
		switch {
		case p.job.existing != nil:
			res.Updated++
		case p.hash != "" && missingByHash[p.hash].ID != 0 && !reattached[missingByHash[p.hash].ID]:
			// Same content at a new path: keep the ID so playlists survive.
			old := missingByHash[p.hash]
			t.ID, t.AddedAt = old.ID, old.AddedAt
			t.KeepCuration(old)
			reattached[old.ID] = true
			res.Moved++
		default:
			res.Added++
		}
		if _, err := s.tracks.Upsert(ctx, t); err != nil {
			return res, fmt.Errorf("save %s: %w", t.Path, err)
		}
	}

	var gone []domain.TrackID
	for _, t := range missing {
		if !reattached[t.ID] {
			gone = append(gone, t.ID)
		}
	}
	if err := s.tracks.Delete(ctx, gone); err != nil {
		return res, fmt.Errorf("delete removed tracks: %w", err)
	}
	res.Removed = len(gone)
	res.Took = s.now().Sub(started)

	s.status.Store(&Status{LastScanAt: s.now(), LastResult: res})
	s.log.Info("library scan finished",
		"added", res.Added, "updated", res.Updated, "moved", res.Moved, "removed", res.Removed,
		"unchanged", res.Unchanged, "failed", res.Failed, "took", res.Took.Round(time.Millisecond))
	return res, nil
}

type probed struct {
	job       job
	result    ProbeResult
	hash      string
	folderArt bool
}

// probeAll reads tags and hashes files using a bounded worker pool.
func (s *Scanner) probeAll(ctx context.Context, jobs []job, res *ScanResult) []probed {
	in := make(chan job)
	out := make(chan probed)
	var failed atomic.Int64
	var wg sync.WaitGroup
	for range s.workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range in {
				abs, err := s.src.AbsPath(j.file.Path)
				if err != nil {
					failed.Add(1)
					continue
				}
				pr, err := s.prober.Probe(abs)
				if err != nil {
					s.log.Warn("probe failed", "path", j.file.Path, "err", err)
					failed.Add(1)
					continue
				}
				h, err := s.hasher.Hash(abs)
				if err != nil {
					s.log.Warn("hash failed", "path", j.file.Path, "err", err)
				}
				_, folderArt := s.src.FindFolderArtwork(path.Dir(j.file.Path))
				select {
				case out <- probed{job: j, result: pr, hash: h, folderArt: folderArt}:
				case <-ctx.Done():
					return
				}
			}
		}()
	}
	go func() {
		defer close(in)
		for _, j := range jobs {
			select {
			case in <- j:
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() {
		wg.Wait()
		close(out)
	}()

	var results []probed
	for p := range out {
		results = append(results, p)
	}
	res.Failed += int(failed.Load())
	return results
}

func (s *Scanner) buildTrack(p probed, artists trackmeta.ArtistIndex) domain.Track {
	meta, source := trackmeta.ResolveInLibrary(p.result.Tags, p.job.file.Path, artists)
	now := s.now()
	t := domain.Track{
		Path:        p.job.file.Path,
		Title:       meta.Title,
		Artist:      meta.Artist,
		Album:       meta.Album,
		AlbumArtist: meta.AlbumArtist,
		TrackNo:     meta.TrackNo,
		Year:        meta.Year,
		Genre:       meta.Genre,
		Duration:    p.result.Duration,
		BitrateKbps: p.result.BitrateKbps,
		Size:        p.job.file.Size,
		ModTime:     p.job.file.ModTime,
		Format:      domain.FormatFromExt(path.Ext(p.job.file.Path)),
		ContentHash: p.hash,
		HasArtwork:  p.result.HasPicture || p.folderArt,
		MetaSource:  source,
		AddedAt:     now,
		UpdatedAt:   now,
	}
	if old := p.job.existing; old != nil {
		t.ID, t.AddedAt = old.ID, old.AddedAt
		t.KeepCuration(*old)
	}
	return t
}
