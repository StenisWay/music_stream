// Package audiotag reads embedded metadata from audio files.
package audiotag

import (
	"fmt"
	"musicstream/internal/shared/trackmeta"
	"os"

	"github.com/dhowden/tag"
)

// Tags is what could be read from the file itself.
type Tags struct {
	Meta    trackmeta.Meta
	Format  string // e.g. "ID3v2.3"
	Picture *Picture
}

// Picture is embedded cover art.
type Picture struct {
	MIMEType string
	Data     []byte
}

// Reader reads and sanitises embedded tags.
type Reader struct{}

func NewReader() *Reader { return &Reader{} }

// Read returns the tags of the file at path. A file without tags is not an
// error: it yields empty Tags so callers can fall back to the filename.
func (r *Reader) Read(path string) (Tags, error) {
	f, err := os.Open(path)
	if err != nil {
		return Tags{}, fmt.Errorf("open %s: %w", path, err)
	}
	defer f.Close()

	m, err := tag.ReadFrom(f)
	if err != nil {
		// Missing, corrupt or unsupported tags (e.g. WMA/ASF) are not fatal:
		// metadata is then inferred from the path.
		return Tags{}, nil
	}

	clean := func(s string) string {
		fixed, ok := RepairText(s, path)
		if !ok {
			return ""
		}
		return trackmeta.CleanValue(fixed)
	}
	trackNo, _ := m.Track()
	out := Tags{
		Format: string(m.Format()),
		Meta: trackmeta.Meta{
			Title:       clean(m.Title()),
			Artist:      clean(m.Artist()),
			Album:       clean(m.Album()),
			AlbumArtist: clean(m.AlbumArtist()),
			Genre:       clean(m.Genre()),
			Year:        m.Year(),
			TrackNo:     trackNo,
		},
	}
	if p := m.Picture(); p != nil && len(p.Data) > 0 {
		out.Picture = &Picture{MIMEType: p.MIMEType, Data: p.Data}
	}
	return out, nil
}

// ReadMeta implements ingest.MetaReader.
func (r *Reader) ReadMeta(path string) (trackmeta.Meta, error) {
	t, err := r.Read(path)
	return t.Meta, err
}
