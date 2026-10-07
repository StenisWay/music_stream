// Package fswatch triggers library rescans when files change (inotify).
package fswatch

import (
	"context"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/fsnotify/fsnotify"
)

// Watcher recursively watches a directory tree and calls onChange once the
// tree has been quiet for the debounce period. Copying an album produces
// hundreds of events; debouncing turns them into a single rescan.
type Watcher struct {
	root     string
	debounce time.Duration
	onChange func(ctx context.Context)
	log      *slog.Logger
}

func New(root string, debounce time.Duration, onChange func(ctx context.Context), log *slog.Logger) *Watcher {
	return &Watcher{root: root, debounce: debounce, onChange: onChange, log: log}
}

// Run blocks until ctx is cancelled.
func (w *Watcher) Run(ctx context.Context) error {
	fw, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	defer fw.Close()
	if err := w.addTree(fw, w.root); err != nil {
		return err
	}

	timer := time.NewTimer(time.Hour)
	timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case ev, ok := <-fw.Events:
			if !ok {
				return nil
			}
			if strings.HasPrefix(filepath.Base(ev.Name), ".") {
				continue
			}
			if ev.Has(fsnotify.Create) {
				if st, err := os.Stat(ev.Name); err == nil && st.IsDir() {
					_ = w.addTree(fw, ev.Name)
				}
			}
			timer.Reset(w.debounce)
		case err, ok := <-fw.Errors:
			if !ok {
				return nil
			}
			w.log.Warn("file watcher error", "err", err)
		case <-timer.C:
			w.onChange(ctx)
		}
	}
}

func (w *Watcher) addTree(fw *fsnotify.Watcher, root string) error {
	return filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // unreadable sub-tree: skip, keep watching the rest
		}
		if d.IsDir() {
			if p != root && strings.HasPrefix(d.Name(), ".") {
				return filepath.SkipDir
			}
			if err := fw.Add(p); err != nil {
				w.log.Warn("cannot watch directory", "dir", p, "err", err)
			}
		}
		return nil
	})
}
