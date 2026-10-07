package app

import (
	"log/slog"
	"net/http"

	"musicstream/internal/platform/httpx"
)

// routes is implemented by every feature's presentation layer.
type routes interface {
	Register(mux *http.ServeMux, authed httpx.Wrap)
}

// publicRoutes is implemented by presentation layers whose endpoints are
// reachable without a token (login/refresh/logout).
type publicRoutes interface {
	Register(mux *http.ServeMux)
}

// newRouter mounts every feature on one mux and applies the cross-cutting
// middleware (outermost first: request log -> panic recovery -> headers).
func newRouter(auth httpx.Authenticator, log *slog.Logger, public publicRoutes, features ...routes) http.Handler {
	mux := http.NewServeMux()
	authed := httpx.RequireAuth(auth)
	public.Register(mux)
	for _, f := range features {
		f.Register(mux, authed)
	}

	var h http.Handler = mux
	h = httpx.SecurityHeaders(h)
	h = httpx.Recover(log)(h)
	h = httpx.RequestLog(log)(h)
	return h
}
