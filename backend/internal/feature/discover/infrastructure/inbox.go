package infrastructure

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"musicstream/internal/feature/discover/application"
)

var _ application.Inbox = (*InboxDir)(nil)

// InboxDir delivers finished downloads into the ingest inbox.
type InboxDir struct {
	dir string
}

func NewInboxDir(dir string) *InboxDir { return &InboxDir{dir: dir} }

// Deliver copies file to a hidden temp name first (ingest and the inbox
// watcher ignore dot files), then renames it into place so ingest only ever
// sees a complete file.
func (d *InboxDir) Deliver(file, name string) error {
	if err := os.MkdirAll(d.dir, 0o755); err != nil {
		return err
	}
	tmp := filepath.Join(d.dir, "."+name+".tmp")
	if err := moveFile(file, tmp); err != nil {
		return fmt.Errorf("copy into inbox: %w", err)
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for n := 1; n < 1000; n++ {
		dst := filepath.Join(d.dir, name)
		if n > 1 {
			dst = filepath.Join(d.dir, fmt.Sprintf("%s (%d)%s", base, n, ext))
		}
		if _, err := os.Lstat(dst); errors.Is(err, os.ErrNotExist) {
			return os.Rename(tmp, dst)
		}
	}
	_ = os.Remove(tmp)
	return fmt.Errorf("no free name for %s in inbox", name)
}

// moveFile renames src to dst, copying when they are on different
// filesystems (the work folder lives in the data dir).
func moveFile(src, dst string) error {
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		_ = os.Remove(dst)
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		_ = os.Remove(dst)
		return err
	}
	return out.Close()
}
