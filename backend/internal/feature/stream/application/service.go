// Package application holds the playback-delivery use cases: range
// streaming of the original file, on-the-fly transcoding and cover art.
// The stream feature has no entities of its own; it serves library tracks.
package application

import (
	"context"
	"fmt"
	"io"
	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/shared"
	"path"
	"time"
)

// TrackCatalog is what streaming needs from the library.
type TrackCatalog interface {
	Get(ctx context.Context, id library.TrackID) (library.Track, error)
}

// MediaStore gives read access to files below the library root.
type MediaStore interface {
	Open(rel string) (io.ReadSeekCloser, error)
	// FindFolderArtwork returns the relative path of a cover image stored
	// next to (or in an Artwork/ folder below) dirRel, if any.
	FindFolderArtwork(dirRel string) (string, bool)
	AbsPath(rel string) (string, error)
}

// Transcoder converts a file to MP3 on the fly (used for WMA).
type Transcoder interface {
	Available() bool
	ToMP3(ctx context.Context, absPath string, w io.Writer) error
}

// PictureReader extracts embedded cover art.
type PictureReader interface {
	Picture(absPath string) (data []byte, mimeType string, err error)
}

// Service resolves tracks to playable bytes.
type Service struct {
	tracks     TrackCatalog
	media      MediaStore
	transcoder Transcoder
	pictures   PictureReader
}

func NewService(tracks TrackCatalog, media MediaStore, t Transcoder, p PictureReader) *Service {
	return &Service{tracks: tracks, media: media, transcoder: t, pictures: p}
}

// Original is a direct (range-capable) stream of the stored file.
type Original struct {
	File     io.ReadSeekCloser
	Name     string
	MIMEType string
	ModTime  time.Time
}

// OpenOriginal opens the raw file for range-request streaming.
func (s *Service) OpenOriginal(ctx context.Context, id library.TrackID) (Original, error) {
	t, err := s.tracks.Get(ctx, id)
	if err != nil {
		return Original{}, err
	}
	f, err := s.media.Open(t.Path)
	if err != nil {
		return Original{}, fmt.Errorf("open track %d: %w", id, err)
	}
	return Original{File: f, Name: path.Base(t.Path), MIMEType: t.Format.MIMEType(), ModTime: t.ModTime}, nil
}

// NeedsTranscode reports whether the client must request the MP3 rendition.
func (s *Service) NeedsTranscode(t library.Track) bool {
	return !t.Format.NativelyPlayableOnAndroid()
}

// CanTranscode reports whether on-the-fly transcoding is configured.
func (s *Service) CanTranscode() bool { return s.transcoder != nil && s.transcoder.Available() }

// TranscodeMP3 writes an MP3 rendition of the track to w.
func (s *Service) TranscodeMP3(ctx context.Context, id library.TrackID, w io.Writer) error {
	if !s.CanTranscode() {
		return fmt.Errorf("transcoding disabled (install ffmpeg): %w", shared.ErrUnsupported)
	}
	t, err := s.tracks.Get(ctx, id)
	if err != nil {
		return err
	}
	abs, err := s.media.AbsPath(t.Path)
	if err != nil {
		return err
	}
	return s.transcoder.ToMP3(ctx, abs, w)
}

// Artwork is a cover image.
type Artwork struct {
	Data     []byte
	MIMEType string
	ModTime  time.Time
}

// TrackArtwork returns embedded art, falling back to a cover image in the
// album folder.
func (s *Service) TrackArtwork(ctx context.Context, id library.TrackID) (Artwork, error) {
	t, err := s.tracks.Get(ctx, id)
	if err != nil {
		return Artwork{}, err
	}
	if t.HasArtwork {
		abs, err := s.media.AbsPath(t.Path)
		if err == nil {
			if data, mime, err := s.pictures.Picture(abs); err == nil && len(data) > 0 {
				return Artwork{Data: data, MIMEType: mime, ModTime: t.ModTime}, nil
			}
		}
	}
	if rel, ok := s.media.FindFolderArtwork(path.Dir(t.Path)); ok {
		f, err := s.media.Open(rel)
		if err == nil {
			defer f.Close()
			data, err := io.ReadAll(io.LimitReader(f, 10<<20))
			if err == nil {
				return Artwork{Data: data, MIMEType: mimeFromExt(path.Ext(rel)), ModTime: t.ModTime}, nil
			}
		}
	}
	return Artwork{}, fmt.Errorf("artwork for track %d: %w", id, shared.ErrNotFound)
}

func mimeFromExt(ext string) string {
	switch ext {
	case ".png", ".PNG":
		return "image/png"
	case ".bmp", ".BMP":
		return "image/bmp"
	case ".webp":
		return "image/webp"
	}
	return "image/jpeg"
}

// CheckTrack verifies the track exists before response headers are sent.
func (s *Service) CheckTrack(ctx context.Context, id library.TrackID) error {
	_, err := s.tracks.Get(ctx, id)
	return err
}
