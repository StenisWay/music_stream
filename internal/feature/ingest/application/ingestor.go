package application

import (
	"context"
	"fmt"
	"log/slog"
	"path/filepath"
	"sync"
	"time"

	"musicstream/internal/feature/ingest/domain"
)

// IngestResult summarises one ingest run.
type IngestResult struct {
	Audio      int
	Duplicates int
	Artwork    int
	Misc       int
	Failed     int
	UndoLog    string
}

// Moved reports whether anything entered the library.
func (r IngestResult) Moved() bool { return r.Audio+r.Artwork > 0 }

// Ingestor moves new files from the inbox into the organised library using
// the same rules as `musicorg`, but aware of the existing catalog:
//   - "Artist - Title" parsing knows every artist already in the library
//   - artist/album folders are reused case-insensitively
//   - content already in the library goes to _duplicates
//   - files still being written (MinAge) and partial downloads are left alone
//
// When files entered the library it asks the catalog to refresh itself.
type Ingestor struct {
	planner *Planner
	exec    *Executor
	catalog Catalog
	refresh CatalogRefresher
	layout  domain.Layout
	minAge  time.Duration
	newLog  MoveLogFactory
	log     *slog.Logger

	mu sync.Mutex // one ingest at a time
}

func NewIngestor(fs FileSystem, reader MetaReader, catalog Catalog, refresh CatalogRefresher, layout domain.Layout,
	minAge time.Duration, newLog MoveLogFactory, log *slog.Logger) *Ingestor {
	return &Ingestor{
		planner: NewPlanner(fs, reader), exec: NewExecutor(fs), catalog: catalog, refresh: refresh,
		layout: layout, minAge: minAge, newLog: newLog, log: log,
	}
}

// Inbox is the watched source folder.
func (i *Ingestor) Inbox() string { return i.layout.SourceRoot }

// Run ingests everything waiting in the inbox, then refreshes the catalog
// if anything entered the library.
func (i *Ingestor) Run(ctx context.Context) (IngestResult, error) {
	res, err := i.ingest(ctx)
	if res.Moved() && i.refresh != nil {
		if rerr := i.refresh.Refresh(ctx); rerr != nil && ctx.Err() == nil {
			i.log.Error("catalog refresh after ingest failed", "err", rerr)
		}
	}
	return res, err
}

func (i *Ingestor) ingest(ctx context.Context) (IngestResult, error) {
	i.mu.Lock()
	defer i.mu.Unlock()
	started := time.Now()

	catalog, err := i.catalog.All(ctx)
	if err != nil {
		return IngestResult{}, fmt.Errorf("load catalog: %w", err)
	}
	hashes := make(map[string]bool, len(catalog))
	artists := make([]string, 0, len(catalog))
	for _, t := range catalog {
		if t.ContentHash != "" {
			hashes[t.ContentHash] = true
		}
		artists = append(artists, t.Artist, t.AlbumArtist)
	}

	plan, err := i.planner.Plan(ctx, i.layout, PlanOptions{
		KnownArtists: artists,
		KnownContent: func(h string) bool { return hashes[h] },
		MinAge:       i.minAge,
	})
	if err != nil {
		return IngestResult{}, err
	}
	res := IngestResult{
		Audio: plan.Count(domain.KindAudio), Duplicates: plan.Count(domain.KindDuplicate),
		Artwork: plan.Count(domain.KindArtwork), Misc: plan.Count(domain.KindMisc),
	}
	if len(plan.Moves) == 0 {
		return res, nil
	}

	moveLog, path, err := i.newLog(started)
	if err != nil {
		return res, fmt.Errorf("open undo log: %w", err)
	}
	defer moveLog.Close()
	res.UndoLog = path

	out, err := i.exec.Apply(ctx, plan, moveLog, i.layout.SourceRoot)
	res.Failed = len(out.Failed)
	for _, f := range out.Failed {
		i.log.Warn("ingest move failed", "err", f)
	}
	for _, m := range plan.Moves {
		if m.Kind == domain.KindAudio || m.Kind == domain.KindDuplicate {
			rel, _ := filepath.Rel(filepath.Dir(i.layout.LibraryRoot), m.To)
			i.log.Info("ingested", "kind", m.Kind, "source", m.Source, "to", rel)
		}
	}
	i.log.Info("ingest finished", "audio", res.Audio, "duplicates", res.Duplicates, "artwork", res.Artwork,
		"misc", res.Misc, "failed", res.Failed, "undo_log", path, "took", time.Since(started).Round(time.Millisecond))
	return res, err
}
