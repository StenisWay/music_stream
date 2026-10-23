package presentation

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"musicstream/internal/feature/library/application"
	"musicstream/internal/feature/library/domain"
	"musicstream/internal/platform/httpx"
	"musicstream/internal/shared"
)

// Handler serves catalog endpoints.
type Handler struct {
	Catalog   *application.Catalog
	Scanner   *application.Scanner
	Presenter Presenter
	Log       *slog.Logger
}

func (h *Handler) Register(mux *http.ServeMux, authed httpx.Wrap) {
	mux.Handle("GET /api/v1/tracks", authed(h.listTracks))
	mux.Handle("GET /api/v1/tracks/{id}", authed(h.getTrack))
	mux.Handle("PATCH /api/v1/tracks/{id}", authed(h.patchTrack))
	mux.Handle("GET /api/v1/artists", authed(h.listArtists))
	mux.Handle("GET /api/v1/artists/{id}", authed(h.getArtist))
	mux.Handle("GET /api/v1/albums", authed(h.listAlbums))
	mux.Handle("GET /api/v1/albums/{id}", authed(h.getAlbum))
	mux.Handle("GET /api/v1/search", authed(h.search))
	mux.Handle("GET /api/v1/categories", authed(h.categories))
	mux.Handle("GET /api/v1/library/duplicates", authed(h.duplicates))
	mux.Handle("POST /api/v1/library/scan", authed(h.triggerScan))
}

func (h *Handler) fail(w http.ResponseWriter, r *http.Request, err error) {
	httpx.WriteDomainError(w, r, h.Log, err)
}

func (h *Handler) listTracks(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := domain.TrackFilter{
		Query:    q.Get("q"),
		ArtistID: domain.ArtistID(q.Get("artistId")),
		AlbumID:  domain.AlbumID(q.Get("albumId")),
		Category: categoryFilter(r),
		Sort:     domain.TrackSort(q.Get("sort")),
	}
	res, err := h.Catalog.ListTracks(r.Context(), f, httpx.PageFromQuery(r))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	page, err := h.Presenter.TrackPage(r.Context(), res)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, page)
}

func (h *Handler) writeTrack(w http.ResponseWriter, r *http.Request, t domain.Track) {
	dtos, err := h.Presenter.Tracks(r.Context(), []domain.Track{t})
	if err != nil {
		h.fail(w, r, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, dtos[0])
}

func (h *Handler) getTrack(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad track id")
		return
	}
	t, err := h.Catalog.GetTrack(r.Context(), domain.TrackID(id))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	h.writeTrack(w, r, t)
}

type patchTrackRequest struct {
	Title       *string `json:"title"`
	Artist      *string `json:"artist"`
	Album       *string `json:"album"`
	AlbumArtist *string `json:"albumArtist"`
	TrackNo     *int    `json:"trackNo"`
	Year        *int    `json:"year"`
	Genre       *string `json:"genre"`
}

func (h *Handler) patchTrack(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad track id")
		return
	}
	var req patchTrackRequest
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	t, err := h.Catalog.UpdateMetadata(r.Context(), domain.TrackID(id), domain.MetadataPatch(req))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	h.writeTrack(w, r, t)
}

func (h *Handler) listArtists(w http.ResponseWriter, r *http.Request) {
	res, err := h.Catalog.ListArtists(r.Context(), r.URL.Query().Get("q"), categoryFilter(r), httpx.PageFromQuery(r))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, ToPage(res, ToArtistDTO))
}

func (h *Handler) getArtist(w http.ResponseWriter, r *http.Request) {
	id := domain.ArtistID(r.PathValue("id"))
	artist, err := h.Catalog.GetArtist(r.Context(), id)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	albums, err := h.Catalog.ListAlbums(r.Context(), id, "", domain.CategoryFilter{}, shared.Page{Limit: shared.MaxPageSize})
	if err != nil {
		h.fail(w, r, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"artist": ToArtistDTO(artist), "albums": ToPage(albums, ToAlbumDTO).Items})
}

func (h *Handler) listAlbums(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	res, err := h.Catalog.ListAlbums(r.Context(), domain.ArtistID(q.Get("artistId")), q.Get("q"), categoryFilter(r), httpx.PageFromQuery(r))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, ToPage(res, ToAlbumDTO))
}

func (h *Handler) getAlbum(w http.ResponseWriter, r *http.Request) {
	album, tracks, err := h.Catalog.GetAlbum(r.Context(), domain.AlbumID(r.PathValue("id")))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	dtos, err := h.Presenter.Tracks(r.Context(), tracks)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"album": ToAlbumDTO(album), "tracks": dtos})
}

func (h *Handler) search(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	res, err := h.Catalog.Search(r.Context(), r.URL.Query().Get("q"), limit)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	tracks, err := h.Presenter.Tracks(r.Context(), res.Tracks)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	artists := make([]ArtistDTO, 0, len(res.Artists))
	for _, x := range res.Artists {
		artists = append(artists, ToArtistDTO(x))
	}
	albums := make([]AlbumDTO, 0, len(res.Albums))
	for _, x := range res.Albums {
		albums = append(albums, ToAlbumDTO(x))
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"tracks": tracks, "artists": artists, "albums": albums})
}

// categoryFilter reads the optional ?type=歌曲&region=華語 parameters.
func categoryFilter(r *http.Request) domain.CategoryFilter {
	q := r.URL.Query()
	return domain.CategoryFilter{Type: q.Get("type"), Region: q.Get("region")}
}

func (h *Handler) categories(w http.ResponseWriter, r *http.Request) {
	counts, err := h.Catalog.Categories(r.Context())
	if err != nil {
		h.fail(w, r, err)
		return
	}
	out := make([]CategoryDTO, 0, len(counts))
	for _, c := range counts {
		out = append(out, CategoryDTO{Type: c.Category.Type, Region: c.Category.Region, TrackCount: c.TrackCount, ArtistCount: c.ArtistCount})
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"categories": out})
}

func (h *Handler) duplicates(w http.ResponseWriter, r *http.Request) {
	groups, err := h.Catalog.FindDuplicates(r.Context())
	if err != nil {
		h.fail(w, r, err)
		return
	}
	type groupDTO struct {
		Reason string     `json:"reason"`
		Tracks []TrackDTO `json:"tracks"`
	}
	out := make([]groupDTO, 0, len(groups))
	for _, g := range groups {
		dtos, err := h.Presenter.Tracks(r.Context(), g.Tracks)
		if err != nil {
			h.fail(w, r, err)
			return
		}
		out = append(out, groupDTO{Reason: string(g.Reason), Tracks: dtos})
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"groups": out})
}

func (h *Handler) triggerScan(w http.ResponseWriter, r *http.Request) {
	if h.Scanner.Status().Running {
		h.fail(w, r, application.ErrScanInProgress)
		return
	}
	// Detach from the request: a full scan can outlive the HTTP call.
	go func() {
		if _, err := h.Scanner.TryFullScan(context.WithoutCancel(r.Context())); err != nil && !errors.Is(err, application.ErrScanInProgress) {
			h.Log.Error("manual scan failed", "err", err)
		}
	}()
	httpx.WriteJSON(w, http.StatusAccepted, map[string]string{"status": "scan started"})
}
