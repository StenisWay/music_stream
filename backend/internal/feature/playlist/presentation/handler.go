// Package presentation is the HTTP delivery of the playlist feature
// (playlists and favorites).
package presentation

import (
	"log/slog"
	library "musicstream/internal/feature/library/domain"
	libraryhttp "musicstream/internal/feature/library/presentation"
	"musicstream/internal/feature/playlist/application"
	"musicstream/internal/feature/playlist/domain"
	"musicstream/internal/platform/httpx"
	"net/http"
)

// Handler serves favorites and playlists.
type Handler struct {
	Playlists *application.Service
	Presenter libraryhttp.Presenter
	Log       *slog.Logger
}

func (h *Handler) Register(mux *http.ServeMux, authed httpx.Wrap) {
	mux.Handle("GET /api/v1/favorites", authed(h.listFavorites))
	mux.Handle("PUT /api/v1/favorites/{trackId}", authed(h.addFavorite))
	mux.Handle("DELETE /api/v1/favorites/{trackId}", authed(h.removeFavorite))
	mux.Handle("GET /api/v1/playlists", authed(h.listPlaylists))
	mux.Handle("POST /api/v1/playlists", authed(h.createPlaylist))
	mux.Handle("GET /api/v1/playlists/{id}", authed(h.getPlaylist))
	mux.Handle("PATCH /api/v1/playlists/{id}", authed(h.renamePlaylist))
	mux.Handle("DELETE /api/v1/playlists/{id}", authed(h.deletePlaylist))
	mux.Handle("PUT /api/v1/playlists/{id}/tracks", authed(h.replacePlaylistTracks))
	mux.Handle("POST /api/v1/playlists/{id}/tracks", authed(h.appendPlaylistTracks))
}

type playlistSummaryDTO struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	TrackCount int    `json:"trackCount"`
	UpdatedAt  int64  `json:"updatedAt"`
}

type playlistDTO struct {
	ID        int64                  `json:"id"`
	Name      string                 `json:"name"`
	Tracks    []libraryhttp.TrackDTO `json:"tracks"`
	UpdatedAt int64                  `json:"updatedAt"`
}

func toTrackIDs(raw []int64) []library.TrackID {
	out := make([]library.TrackID, len(raw))
	for i, v := range raw {
		out[i] = library.TrackID(v)
	}
	return out
}

func (h *Handler) listFavorites(w http.ResponseWriter, r *http.Request) {
	res, err := h.Playlists.ListFavorites(r.Context(), httpx.UserFrom(r.Context()), httpx.PageFromQuery(r))
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	page, err := h.Presenter.TrackPage(r.Context(), res)
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, page)
}

func (h *Handler) addFavorite(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "trackId")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad track id")
		return
	}
	if err := h.Playlists.AddFavorite(r.Context(), httpx.UserFrom(r.Context()), library.TrackID(id)); err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) removeFavorite(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "trackId")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad track id")
		return
	}
	if err := h.Playlists.RemoveFavorite(r.Context(), httpx.UserFrom(r.Context()), library.TrackID(id)); err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) listPlaylists(w http.ResponseWriter, r *http.Request) {
	list, err := h.Playlists.List(r.Context(), httpx.UserFrom(r.Context()))
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	out := make([]playlistSummaryDTO, 0, len(list))
	for _, p := range list {
		out = append(out, playlistSummaryDTO{ID: int64(p.ID), Name: p.Name, TrackCount: p.TrackCount, UpdatedAt: httpx.Millis(p.UpdatedAt)})
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": out})
}

type playlistRequest struct {
	Name     string  `json:"name"`
	TrackIDs []int64 `json:"trackIds"`
}

type trackIDsRequest struct {
	TrackIDs []int64 `json:"trackIds"`
}

func (h *Handler) createPlaylist(w http.ResponseWriter, r *http.Request) {
	var req playlistRequest
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	p, err := h.Playlists.Create(r.Context(), httpx.UserFrom(r.Context()), req.Name, toTrackIDs(req.TrackIDs))
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	h.writePlaylist(w, r, p.ID, http.StatusCreated)
}

func (h *Handler) getPlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad playlist id")
		return
	}
	h.writePlaylist(w, r, domain.PlaylistID(id), http.StatusOK)
}

func (h *Handler) writePlaylist(w http.ResponseWriter, r *http.Request, id domain.PlaylistID, status int) {
	p, tracks, err := h.Playlists.Get(r.Context(), httpx.UserFrom(r.Context()), id)
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	dtos, err := h.Presenter.Tracks(r.Context(), tracks)
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	httpx.WriteJSON(w, status, playlistDTO{ID: int64(p.ID), Name: p.Name, Tracks: dtos, UpdatedAt: httpx.Millis(p.UpdatedAt)})
}

func (h *Handler) renamePlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad playlist id")
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	if err := h.Playlists.Rename(r.Context(), httpx.UserFrom(r.Context()), domain.PlaylistID(id), req.Name); err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	h.writePlaylist(w, r, domain.PlaylistID(id), http.StatusOK)
}

func (h *Handler) deletePlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad playlist id")
		return
	}
	if err := h.Playlists.Delete(r.Context(), httpx.UserFrom(r.Context()), domain.PlaylistID(id)); err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) replacePlaylistTracks(w http.ResponseWriter, r *http.Request) {
	h.modifyPlaylistTracks(w, r, false)
}

func (h *Handler) appendPlaylistTracks(w http.ResponseWriter, r *http.Request) {
	h.modifyPlaylistTracks(w, r, true)
}

func (h *Handler) modifyPlaylistTracks(w http.ResponseWriter, r *http.Request, appendMode bool) {
	id, ok := httpx.PathInt64(r, "id")
	if !ok {
		httpx.WriteError(w, http.StatusBadRequest, "invalid_argument", "bad playlist id")
		return
	}
	var req trackIDsRequest
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	owner, pid, ids := httpx.UserFrom(r.Context()), domain.PlaylistID(id), toTrackIDs(req.TrackIDs)
	var err error
	if appendMode {
		err = h.Playlists.AppendTracks(r.Context(), owner, pid, ids)
	} else {
		err = h.Playlists.ReplaceTracks(r.Context(), owner, pid, ids)
	}
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	h.writePlaylist(w, r, pid, http.StatusOK)
}
