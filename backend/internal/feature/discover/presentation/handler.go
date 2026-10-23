// Package presentation is the HTTP delivery of the discover feature.
package presentation

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"musicstream/internal/feature/discover/application"
	"musicstream/internal/feature/discover/domain"
	"musicstream/internal/platform/httpx"
)

type Handler struct {
	Discover *application.Service
	Log      *slog.Logger
}

func (h *Handler) Register(mux *http.ServeMux, authed httpx.Wrap) {
	mux.Handle("GET /api/v1/discover/search", authed(h.search))
	mux.Handle("GET /api/v1/discover/downloads", authed(h.listDownloads))
	mux.Handle("POST /api/v1/discover/downloads", authed(h.addDownload))
}

func (h *Handler) fail(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrUnavailable):
		httpx.WriteError(w, http.StatusServiceUnavailable, "unavailable", "yt-dlp is not installed or no inbox is configured")
	case errors.Is(err, domain.ErrUpstream):
		h.Log.WarnContext(r.Context(), "online search failed", "err", err)
		httpx.WriteError(w, http.StatusBadGateway, "upstream", "the online source did not respond")
	default:
		httpx.WriteDomainError(w, r, h.Log, err)
	}
}

type candidateDTO struct {
	Source       string `json:"source"`
	URL          string `json:"url"`
	Title        string `json:"title"`
	Uploader     string `json:"uploader"`
	DurationSec  int    `json:"durationSec"`
	ThumbnailURL string `json:"thumbnailUrl,omitempty"`
	Artist       string `json:"artist"`
	Track        string `json:"track"`
}

func (h *Handler) search(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	src, err := domain.ParseSource(q.Get("source"))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	limit, _ := strconv.Atoi(q.Get("limit"))
	res, err := h.Discover.Search(r.Context(), src, q.Get("q"), limit)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	items := make([]candidateDTO, 0, len(res))
	for _, c := range res {
		items = append(items, candidateDTO{
			Source: string(c.Source), URL: c.URL, Title: c.Title, Uploader: c.Uploader,
			DurationSec: int(c.Duration.Seconds()), ThumbnailURL: c.Thumbnail,
			Artist: c.Guess.Artist, Track: c.Guess.Title,
		})
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": items})
}

type downloadRequest struct {
	URL    string `json:"url"`
	Artist string `json:"artist"`
	Title  string `json:"title"`
	Album  string `json:"album"`
}

type jobDTO struct {
	ID        int64  `json:"id"`
	URL       string `json:"url"`
	Artist    string `json:"artist"`
	Title     string `json:"title"`
	Album     string `json:"album,omitempty"`
	Status    string `json:"status"`
	Error     string `json:"error,omitempty"`
	CreatedAt int64  `json:"createdAt"`
	UpdatedAt int64  `json:"updatedAt"`
}

func toJobDTO(j domain.Job) jobDTO {
	return jobDTO{
		ID: int64(j.ID), URL: j.URL, Artist: j.Info.Artist, Title: j.Info.Title, Album: j.Info.Album,
		Status: string(j.Status), Error: j.Error,
		CreatedAt: httpx.Millis(j.CreatedAt), UpdatedAt: httpx.Millis(j.UpdatedAt),
	}
}

func (h *Handler) addDownload(w http.ResponseWriter, r *http.Request) {
	var req downloadRequest
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	j, err := h.Discover.Enqueue(req.URL, domain.TrackInfo{Artist: req.Artist, Title: req.Title, Album: req.Album})
	if err != nil {
		h.fail(w, r, err)
		return
	}
	httpx.WriteJSON(w, http.StatusAccepted, toJobDTO(j))
}

func (h *Handler) listDownloads(w http.ResponseWriter, r *http.Request) {
	jobs := h.Discover.Jobs()
	items := make([]jobDTO, 0, len(jobs))
	for _, j := range jobs {
		items = append(items, toJobDTO(j))
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": items})
}
