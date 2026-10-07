// Package presentation is the HTTP delivery of the auth feature.
package presentation

import (
	"log/slog"
	"net/http"
	"time"

	"musicstream/internal/feature/auth/application"
	"musicstream/internal/platform/httpx"
)

type Handler struct {
	Auth *application.Service
	Log  *slog.Logger
}

func (h *Handler) Register(mux *http.ServeMux) {
	limit := httpx.NewIPLimiter(12*time.Second, 5) // ~5 attempts/min/IP against password guessing
	mux.Handle("POST /api/v1/auth/login", limit.Wrap(http.HandlerFunc(h.login)))
	mux.Handle("POST /api/v1/auth/refresh", limit.Wrap(http.HandlerFunc(h.refresh)))
	mux.HandleFunc("POST /api/v1/auth/logout", h.logout)
}

type loginRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceName string `json:"deviceName"`
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
}

type tokenDTO struct {
	TokenType        string `json:"tokenType"`
	AccessToken      string `json:"accessToken"`
	ExpiresIn        int64  `json:"expiresIn"` // seconds
	RefreshToken     string `json:"refreshToken"`
	RefreshExpiresAt int64  `json:"refreshExpiresAt"`
}

func toTokenDTO(p application.TokenPair) tokenDTO {
	return tokenDTO{
		TokenType: "Bearer", AccessToken: p.AccessToken,
		ExpiresIn:    int64(time.Until(p.AccessExpiresAt).Seconds()),
		RefreshToken: p.RefreshToken, RefreshExpiresAt: httpx.Millis(p.RefreshExpiresAt),
	}
}

func (h *Handler) login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	pair, err := h.Auth.Login(r.Context(), req.Username, req.Password, req.DeviceName)
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, toTokenDTO(pair))
}

func (h *Handler) refresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	pair, err := h.Auth.Refresh(r.Context(), req.RefreshToken)
	if err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, toTokenDTO(pair))
}

func (h *Handler) logout(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !httpx.DecodeJSON(w, r, &req) {
		return
	}
	if err := h.Auth.Logout(r.Context(), req.RefreshToken); err != nil {
		httpx.WriteDomainError(w, r, h.Log, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
