// Package httpx holds HTTP plumbing shared by every feature's delivery
// adapter: JSON responses, shared-error mapping, request decoding and the
// cross-cutting middleware. It contains no business rules.
package httpx

import (
	"encoding/json"
	"errors"
	"log/slog"
	"musicstream/internal/shared"
	"net/http"
	"strconv"
	"time"
)

type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

func WriteError(w http.ResponseWriter, status int, code, msg string) {
	WriteJSON(w, status, errorBody{Error: errorDetail{Code: code, Message: msg}})
}

// WriteDomainError maps shared error categories to HTTP. Unknown errors are
// logged and reported as 500 without leaking internals.
func WriteDomainError(w http.ResponseWriter, r *http.Request, log *slog.Logger, err error) {
	switch {
	case errors.Is(err, shared.ErrNotFound):
		WriteError(w, http.StatusNotFound, "not_found", "resource not found")
	case errors.Is(err, shared.ErrInvalidArgument):
		WriteError(w, http.StatusBadRequest, "invalid_argument", err.Error())
	case errors.Is(err, shared.ErrConflict):
		WriteError(w, http.StatusConflict, "conflict", err.Error())
	case errors.Is(err, shared.ErrUnauthenticated):
		WriteError(w, http.StatusUnauthorized, "unauthenticated", "invalid credentials")
	case errors.Is(err, shared.ErrUnsupported):
		WriteError(w, http.StatusUnsupportedMediaType, "unsupported", err.Error())
	default:
		log.ErrorContext(r.Context(), "request failed", "method", r.Method, "path", r.URL.Path, "err", err)
		WriteError(w, http.StatusInternalServerError, "internal", "internal server error")
	}
}

// DecodeJSON reads a size-limited JSON body and rejects unknown fields.
func DecodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		WriteError(w, http.StatusBadRequest, "invalid_json", err.Error())
		return false
	}
	return true
}

func PageFromQuery(r *http.Request) shared.Page {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	offset, _ := strconv.Atoi(q.Get("offset"))
	return shared.Page{Limit: limit, Offset: offset}.Normalize()
}

func PathInt64(r *http.Request, name string) (int64, bool) {
	v, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	return v, err == nil && v > 0
}

// Millis converts a time to Unix milliseconds (0 for the zero time).
func Millis(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}
