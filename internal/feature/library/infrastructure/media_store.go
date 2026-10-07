package infrastructure

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"musicstream/internal/feature/library/application"
	"musicstream/internal/feature/library/domain"
	"musicstream/internal/shared"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// MediaStore gives safe access to the music folder. It is rooted at the
// library directory: all methods take slash-separated paths relative to the
// root and refuse to escape it.
type MediaStore struct {
	root string
	fsys *os.Root
}

func OpenMediaStore(root string) (*MediaStore, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	r, err := os.OpenRoot(abs)
	if err != nil {
		return nil, fmt.Errorf("open library root: %w", err)
	}
	return &MediaStore{root: abs, fsys: r}, nil
}

func (s *MediaStore) Root() string { return s.root }
func (s *MediaStore) Close() error { return s.fsys.Close() }

// ListAudioFiles walks the library (skipping dot-folders) and returns
// supported audio files.
func (s *MediaStore) ListAudioFiles(ctx context.Context) ([]application.FileInfo, error) {
	var out []application.FileInfo
	err := fs.WalkDir(s.fsys.FS(), ".", func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if d.IsDir() {
			if p != "." && strings.HasPrefix(d.Name(), ".") {
				return fs.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() || domain.FormatFromExt(path.Ext(p)) == "" {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		out = append(out, application.FileInfo{Path: p, Size: info.Size(), ModTime: info.ModTime()})
		return nil
	})
	return out, err
}

func (s *MediaStore) Stat(rel string) (application.FileInfo, error) {
	info, err := s.fsys.Stat(filepath.FromSlash(rel))
	if err != nil {
		return application.FileInfo{}, mapErr(err)
	}
	return application.FileInfo{Path: rel, Size: info.Size(), ModTime: info.ModTime()}, nil
}

// AbsPath validates rel and returns its absolute path (needed by libraries
// that open files by name).
func (s *MediaStore) AbsPath(rel string) (string, error) {
	clean := path.Clean("/" + rel)[1:]
	if clean == "" || !fs.ValidPath(clean) {
		return "", fmt.Errorf("invalid path %q: %w", rel, shared.ErrInvalidArgument)
	}
	return filepath.Join(s.root, filepath.FromSlash(clean)), nil
}

func (s *MediaStore) Open(rel string) (io.ReadSeekCloser, error) {
	f, err := s.fsys.Open(filepath.FromSlash(rel))
	if err != nil {
		return nil, mapErr(err)
	}
	return f, nil
}

var coverNames = []string{"cover", "folder", "front", "albumart"}

// FindFolderArtwork looks for cover.jpg / folder.png ... in dirRel and in
// dirRel/Artwork (where the organizer puts booklet scans).
func (s *MediaStore) FindFolderArtwork(dirRel string) (string, bool) {
	for _, dir := range []string{dirRel, path.Join(dirRel, "Artwork")} {
		entries, err := fs.ReadDir(s.fsys.FS(), dir)
		if err != nil {
			continue
		}
		var firstImage string
		for _, e := range entries {
			name := strings.ToLower(e.Name())
			ext := path.Ext(name)
			if e.IsDir() || (ext != ".jpg" && ext != ".jpeg" && ext != ".png") {
				continue
			}
			for _, c := range coverNames {
				if strings.TrimSuffix(name, ext) == c {
					return path.Join(dir, e.Name()), true
				}
			}
			if firstImage == "" {
				firstImage = path.Join(dir, e.Name())
			}
		}
		if firstImage != "" && dir != dirRel {
			return firstImage, true
		}
	}
	return "", false
}

func mapErr(err error) error {
	if errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("%w: %v", shared.ErrNotFound, err)
	}
	return err
}
