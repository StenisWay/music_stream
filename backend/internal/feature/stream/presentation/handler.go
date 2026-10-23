// Package presentation is the HTTP delivery of the stream feature.
package presentation

import (
	"fmt"
	"log/slog"
	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/feature/stream/application"
	"musicstream/internal/platform/httpx"
	"musicstream/internal/shared"
	"net/http"
	"strconv"
)

// Handler serves audio and artwork.
type Handler struct {
	Stream *application.Service
	Log    *slog.Logger
}

func (h *Handler) Register(mux *http.ServeMux, authed httpx.Wrap) {
	mux.Handle("GET /api/v1/tracks/{id}/stream", authed(h.streamTrack))
	mux.Handle("GET /api/v1/tracks/{id}/artwork", authed(h.trackArtwork))
}

// streamTrack serves the original file with full HTTP Range support (needed
// for seeking in ExoPlayer), or an on-the-fly MP3 rendition for formats
// Android cannot decode.
func (h *Handler) streamTrack(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad track id")
		return
	}
	if r.URL.Query().Get("transcode") == "mp3" {
		h.streamTranscoded(w, r, library.TrackID(id))
		return
	}
	orig, err := h.Stream.OpenOriginal(r.Context(), library.TrackID(id))
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	defer orig.File.Close()
	w.Header().Set("Content-Type", orig.MIMEType)
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Header().Set("Accept-Ranges", "bytes")
	// ServeContent handles Range, If-Range, If-Modified-Since and HEAD.
	http.ServeContent(w, r, orig.Name, orig.ModTime, orig.File)
}

func (h *Handler) streamTranscoded(w http.ResponseWriter, r *http.Request, id library.TrackID) {
	if !h.Stream.CanTranscode() {
		httpx.WriteDomainError(w, r, h.Log, fmt.Errorf("transcoding unavailable: %w", shared.ErrUnsupported))
		return
	}
	if err := h.Stream.CheckTrack(r.Context(), id); err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	w.Header().Set("Content-Type", "audio/mpeg")
	w.Header().Set("Accept-Ranges", "none")
	w.Header().Set("Cache-Control", "private, no-store")
	if r.Method == http.MethodHead {
		return
	}
	if err := h.Stream.TranscodeMP3(r.Context(), id, w); err != nil && r.Context().Err() == nil {
		// Headers are already sent; all we can do is log and abort the body.
		h.Log.Error("transcode failed", "track", id, "err", err)
	}
}

func (h *Handler) trackArtwork(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad track id")
		return
	}
	art, err := h.Stream.TrackArtwork(r.Context(), library.TrackID(id))
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	etag := `"` + strconv.FormatInt(id, 36) + "-" + strconv.FormatInt(art.ModTime.Unix(), 36) + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, max-age=604800")
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", art.MIMEType)
	w.Header().Set("Content-Length", strconv.Itoa(len(art.Data)))
	_, _ = w.Write(art.Data)
}
