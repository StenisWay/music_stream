package audiotag

import (
	"fmt"
	"musicstream/internal/shared/trackmeta"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/dhowden/tag"
)

// Info is everything that can be learned by reading an audio file.
type Info struct {
	Tags        trackmeta.Meta
	Duration    time.Duration
	BitrateKbps int
	HasPicture  bool
}

// Prober reads tags, duration and cover art. The library feature adapts it
// to its Prober port (library/infrastructure.TagProber); it satisfies the
// stream feature's PictureReader port directly.
type Prober struct {
	reader *Reader
}

func NewProber() *Prober { return &Prober{reader: NewReader()} }

func (p *Prober) Probe(absPath string) (Info, error) {
	f, err := os.Open(absPath)
	if err != nil {
		return Info{}, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return Info{}, err
	}

	switch strings.ToLower(filepath.Ext(absPath)) {
	case ".wma":
		info, err := readASF(f)
		if err != nil {
			return Info{}, fmt.Errorf("read asf header: %w", err)
		}
		clean := func(s string) string { return trackmeta.CleanValue(s) }
		return Info{
			Tags:        trackmeta.Meta{Title: clean(info.title), Artist: clean(info.author)},
			Duration:    info.duration,
			BitrateKbps: info.bitrateKbps,
		}, nil
	case ".mp3":
		tags, err := p.reader.Read(absPath)
		if err != nil {
			return Info{}, err
		}
		d, kbps, err := mp3Duration(f, st.Size())
		if err != nil {
			// Keep the track; the player will learn the duration itself.
			d, kbps = 0, 0
		}
		return Info{Tags: tags.Meta, Duration: d, BitrateKbps: kbps, HasPicture: tags.Picture != nil}, nil
	default:
		tags, err := p.reader.Read(absPath)
		if err != nil {
			return Info{}, err
		}
		return Info{Tags: tags.Meta, HasPicture: tags.Picture != nil}, nil
	}
}

// Picture returns the embedded cover art of a file.
func (p *Prober) Picture(absPath string) ([]byte, string, error) {
	f, err := os.Open(absPath)
	if err != nil {
		return nil, "", err
	}
	defer f.Close()
	m, err := tag.ReadFrom(f)
	if err != nil {
		return nil, "", err
	}
	pic := m.Picture()
	if pic == nil {
		return nil, "", fmt.Errorf("no embedded picture")
	}
	mime := pic.MIMEType
	if mime == "" || !strings.HasPrefix(mime, "image/") {
		mime = "image/jpeg"
	}
	return pic.Data, mime, nil
}
