// Package infrastructure implements the ingest ports on the local disk:
// file access and the append-only undo log.
package infrastructure

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"musicstream/internal/feature/ingest/application"
	"musicstream/internal/platform/filehash"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// LocalFS implements application.FileSystem on the local disk.
type LocalFS struct {
	// Exclude lists absolute directories that ListFiles must not descend into
	// (e.g. the library root when it lives inside the source root).
	Exclude []string
}

func NewLocalFS(exclude ...string) *LocalFS { return &LocalFS{Exclude: exclude} }

func (f *LocalFS) ListFiles(ctx context.Context, root string) ([]application.FileEntry, error) {
	var out []application.FileEntry
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if d.IsDir() {
			for _, ex := range f.Exclude {
				if path == ex {
					return filepath.SkipDir
				}
			}
			if d.Name() == ".musicorg" {
				return filepath.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		out = append(out, application.FileEntry{Path: path, Rel: rel, Size: info.Size(), ModTime: info.ModTime()})
		return nil
	})
	return out, err
}

func (f *LocalFS) Exists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

// Move renames src to dst, creating parent directories. Falls back to
// copy+remove when crossing filesystems.
func (f *LocalFS) Move(src, dst string) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	err := os.Rename(src, dst)
	var linkErr *os.LinkError
	if err == nil || !errors.As(err, &linkErr) {
		return err
	}
	if err := copyFile(src, dst); err != nil {
		return err
	}
	return os.Remove(src)
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(dst)
		return err
	}
	return out.Close()
}

// Hash is the content hash shared with the library scanner.
func (f *LocalFS) Hash(path string) (string, error) { return filehash.SHA256(path) }

// RemoveEmptyDirs deletes empty directories below root (deepest first). The
// root itself is kept (it may be a watched inbox). It never deletes files.
func (f *LocalFS) RemoveEmptyDirs(root string) (int, error) {
	var dirs []string
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			dirs = append(dirs, path)
		}
		return nil
	})
	if err != nil {
		return 0, err
	}
	sort.Slice(dirs, func(i, j int) bool { return len(dirs[i]) > len(dirs[j]) })
	removed := 0
	for _, d := range dirs {
		if d == root {
			continue
		}
		entries, err := os.ReadDir(d)
		if err != nil {
			return removed, err
		}
		if len(entries) == 0 {
			if err := os.Remove(d); err != nil {
				return removed, fmt.Errorf("remove %s: %w", d, err)
			}
			removed++
		}
	}
	return removed, nil
}

// ExistingChild returns the name of an entry in dir equal to name ignoring
// case. Exact matches win; otherwise the lexically first match is used so
// the choice is deterministic.
func (f *LocalFS) ExistingChild(dir, name string) (string, bool) {
	entries, err := os.ReadDir(dir) // sorted by name
	if err != nil {
		return "", false
	}
	found := ""
	for _, e := range entries {
		if e.Name() == name {
			return name, true
		}
		if found == "" && strings.EqualFold(e.Name(), name) {
			found = e.Name()
		}
	}
	return found, found != ""
}
