// Command musicorg reorganises a messy music folder into a clean
// <Type>/<Region>/<Artist>/<Album>/<Title> library that the streaming server can index.
//
//	musicorg -src ../music_data/music -dest ../music_data            # dry run, writes plan report
//	musicorg -src ../music_data/music -dest ../music_data -apply     # move files, write undo log
//	musicorg -undo ../music_data/.musicorg/undo-20261006-230000.jsonl
package main

import (
	"context"
	"encoding/csv"
	"flag"
	"fmt"
	"log/slog"
	"musicstream/internal/feature/ingest/application"
	"musicstream/internal/feature/ingest/domain"
	"musicstream/internal/feature/ingest/infrastructure"
	"musicstream/internal/platform/audiotag"
	"musicstream/internal/shared/trackmeta"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"time"
)

func main() {
	if err := run(); err != nil {
		slog.Error("musicorg failed", "err", err)
		os.Exit(1)
	}
}

func run() error {
	src := flag.String("src", "", "messy source folder")
	dest := flag.String("dest", "", "output root; library/, _misc/ and _duplicates/ are created here")
	apply := flag.Bool("apply", false, "actually move files (default: dry run)")
	undo := flag.String("undo", "", "undo log to revert")
	flag.Parse()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	fs := infrastructure.NewLocalFS()
	exec := application.NewExecutor(fs)

	if *undo != "" {
		moves, err := infrastructure.ReadMoveLog(*undo)
		if err != nil {
			return err
		}
		res, err := exec.Undo(ctx, moves)
		slog.Info("undo finished", "restored", res.Moved, "failed", len(res.Failed))
		return err
	}

	if *src == "" || *dest == "" {
		flag.Usage()
		return fmt.Errorf("-src and -dest are required")
	}
	srcAbs, err := filepath.Abs(*src)
	if err != nil {
		return err
	}
	destAbs, err := filepath.Abs(*dest)
	if err != nil {
		return err
	}
	layout := domain.Layout{
		SourceRoot:     srcAbs,
		LibraryRoot:    filepath.Join(destAbs, "library"),
		MiscRoot:       filepath.Join(destAbs, "_misc"),
		DuplicatesRoot: filepath.Join(destAbs, "_duplicates"),
	}
	fs.Exclude = []string{layout.LibraryRoot, layout.MiscRoot, layout.DuplicatesRoot}

	started := time.Now()
	known, err := existingCategories(ctx, fs, layout.LibraryRoot)
	if err != nil {
		return err
	}
	plan, err := application.NewPlanner(fs, audiotag.NewReader()).Plan(ctx, layout, application.PlanOptions{KnownCategories: known})
	if err != nil {
		return err
	}
	stamp := started.Format("20060102-150405")
	stateDir := filepath.Join(destAbs, ".musicorg")
	reportPath := filepath.Join(stateDir, "plan-"+stamp+".tsv")
	if err := writeReport(reportPath, plan, destAbs); err != nil {
		return err
	}
	slog.Info("plan ready",
		"audio", plan.Count(domain.KindAudio),
		"artwork", plan.Count(domain.KindArtwork),
		"duplicates", plan.Count(domain.KindDuplicate),
		"misc", plan.Count(domain.KindMisc),
		"report", reportPath,
		"took", time.Since(started).Round(time.Millisecond))

	if !*apply {
		slog.Info("dry run only; re-run with -apply to move files")
		return nil
	}

	logPath := filepath.Join(stateDir, "undo-"+stamp+".jsonl")
	moveLog, err := infrastructure.CreateMoveLog(logPath)
	if err != nil {
		return err
	}
	defer moveLog.Close()
	res, err := exec.Apply(ctx, plan, moveLog, srcAbs)
	for _, f := range res.Failed {
		slog.Warn("move failed", "err", f)
	}
	slog.Info("apply finished", "moved", res.Moved, "removed_dirs", res.RemovedDirs, "failed", len(res.Failed), "undo_log", logPath)
	return err
}

// existingCategories records where the artists already in the library live,
// so new tracks join them.
func existingCategories(ctx context.Context, fs *infrastructure.LocalFS, libraryRoot string) (trackmeta.ArtistCategories, error) {
	known := trackmeta.ArtistCategories{}
	if !fs.Exists(libraryRoot) {
		return known, nil
	}
	files, err := infrastructure.NewLocalFS().ListFiles(ctx, libraryRoot)
	if err != nil {
		return nil, fmt.Errorf("list library: %w", err)
	}
	for _, f := range files {
		if domain.IsAudio(f.Path) {
			known.Add(filepath.ToSlash(f.Rel))
		}
	}
	return known, nil
}

func writeReport(path string, plan domain.Plan, root string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	w := csv.NewWriter(f)
	w.Comma = '\t'
	_ = w.Write([]string{"kind", "source", "artist", "album", "track", "title", "from", "to"})
	rel := func(p string) string {
		if r, err := filepath.Rel(root, p); err == nil {
			return r
		}
		return p
	}
	for _, m := range plan.Moves {
		_ = w.Write([]string{
			string(m.Kind), string(m.Source), m.Meta.GroupingArtist(), m.Meta.Album,
			strconv.Itoa(m.Meta.TrackNo), m.Meta.Title, rel(m.From), rel(m.To),
		})
	}
	w.Flush()
	return w.Error()
}
