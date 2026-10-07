// Package presentation is the HTTP delivery of the system feature: health
// check and server info.
package presentation

import (
	"log/slog"
	"net/http"

	"musicstream/internal/feature/system/application"
	"musicstream/internal/platform/httpx"
)

type Handler struct {
	Status *application.StatusService
	Log    *slog.Logger
}

func (h *Handler) Register(mux *http.ServeMux, authed httpx.Wrap) {
	mux.HandleFunc("GET /healthz", h.health) // public: used by monitors
	mux.Handle("GET /api/v1/server/info", authed(h.info))
}

func (h *Handler) health(w http.ResponseWriter, _ *http.Request) {
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "ok", "version": h.Status.Version()})
}

func (h *Handler) info(w http.ResponseWriter, r *http.Request) {
	st, err := h.Status.Status(r.Context())
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"version":            st.Version,
		"trackCount":         st.TrackCount,
		"scanning":           st.Scanning,
		"lastScanAt":         httpx.Millis(st.LastScanAt),
		"transcodeAvailable": st.TranscodeAvailable,
	})
}
