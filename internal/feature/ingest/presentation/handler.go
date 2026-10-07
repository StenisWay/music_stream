// Package presentation is the HTTP delivery of the ingest feature.
package presentation

import (
	"log/slog"
	"net/http"

	"musicstream/internal/feature/ingest/application"
	"musicstream/internal/platform/httpx"
)

type Handler struct {
	Ingestor *application.Ingestor // nil when no inbox is configured
	Log      *slog.Logger
}

func (h *Handler) Register(mux *http.ServeMux, authed httpx.Wrap) {
	mux.Handle("POST /api/v1/library/ingest", authed(h.trigger))
}

// trigger classifies files waiting in the inbox right away (normally this
// happens automatically a few seconds after they appear).
func (h *Handler) trigger(w http.ResponseWriter, r *http.Request) {
	if h.Ingestor == nil {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "no inbox configured")
		return
	}
	res, err := h.Ingestor.Run(r.Context())
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]int{
		"audio": res.Audio, "duplicates": res.Duplicates, "artwork": res.Artwork, "misc": res.Misc, "failed": res.Failed,
	})
}
