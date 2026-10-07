// Package presentation is the HTTP delivery of the endpoint feature.
package presentation

import (
	"net/http"

	"musicstream/internal/feature/endpoint/application"
	"musicstream/internal/platform/httpx"
)

type Handler struct {
	Publisher *application.Publisher // nil when publishing is disabled
}

func (h *Handler) Register(mux *http.ServeMux, authed httpx.Wrap) {
	mux.Handle("GET /api/v1/server/endpoint", authed(h.current))
}

type endpointDTO struct {
	PublicHost string   `json:"publicHost"`
	LANHosts   []string `json:"lanHosts"`
	Port       int      `json:"port"`
	Scheme     string   `json:"scheme"`
	CertPin    string   `json:"certPin"`
	UpdatedAt  int64    `json:"updatedAt"`
}

func (h *Handler) current(w http.ResponseWriter, _ *http.Request) {
	if h.Publisher == nil {
		httpx.WriteError(w, http.StatusNotFound, "not_found", "endpoint publishing disabled")
		return
	}
	e := h.Publisher.Current()
	httpx.WriteJSON(w, http.StatusOK, endpointDTO{
		PublicHost: e.PublicHost, LANHosts: e.LANHosts, Port: e.Port, Scheme: e.Scheme,
		CertPin: e.CertPin, UpdatedAt: httpx.Millis(e.UpdatedAt),
	})
}
