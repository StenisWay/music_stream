package httpx

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"musicstream/internal/shared"
	"net"
	"net/http"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

type ctxKey int

const (
	ctxUserID ctxKey = iota
	ctxRequestID
)

func UserFrom(ctx context.Context) shared.UserID {
	id, _ := ctx.Value(ctxUserID).(shared.UserID)
	return id
}

// statusRecorder captures the status code and size for access logs.
type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	n, err := s.ResponseWriter.Write(b)
	s.bytes += int64(n)
	return n, err
}

// Unwrap lets http.ResponseController reach Flush on the real writer.
func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

func RequestLog(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			id := r.Header.Get("X-Request-ID")
			if id == "" || len(id) > 64 {
				b := make([]byte, 8)
				_, _ = rand.Read(b)
				id = hex.EncodeToString(b)
			}
			w.Header().Set("X-Request-ID", id)
			rec := &statusRecorder{ResponseWriter: w}
			next.ServeHTTP(rec, r.WithContext(context.WithValue(r.Context(), ctxRequestID, id)))
			log.Info("http",
				"id", id, "method", r.Method, "path", r.URL.Path, "status", rec.status,
				"bytes", rec.bytes, "dur", time.Since(start).Round(time.Microsecond), "remote", ClientIP(r))
		})
	}
}

func Recover(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			defer func() {
				if v := recover(); v != nil {
					if v == http.ErrAbortHandler {
						panic(v)
					}
					log.Error("panic", "err", v, "stack", string(debug.Stack()))
					WriteError(w, http.StatusInternalServerError, "internal", "internal server error")
				}
			}()
			next.ServeHTTP(w, r)
		})
	}
}

func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		if r.TLS != nil {
			h.Set("Strict-Transport-Security", "max-age=31536000")
		}
		next.ServeHTTP(w, r)
	})
}

// Authenticator validates bearer tokens.
type Authenticator interface {
	Authenticate(token string) (shared.UserID, error)
}

// Wrap decorates a handler (e.g. with authentication).
type Wrap func(http.HandlerFunc) http.Handler

// RequireAuth returns a Wrap that rejects requests without a valid bearer
// token and puts the caller's user id into the request context.
func RequireAuth(a Authenticator) Wrap {
	return func(next http.HandlerFunc) http.Handler { return requireAuth(a, next) }
}

func requireAuth(a Authenticator, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || token == "" {
			w.Header().Set("WWW-Authenticate", `Bearer realm="musicstream"`)
			WriteError(w, http.StatusUnauthorized, "unauthenticated", "missing bearer token")
			return
		}
		uid, err := a.Authenticate(token)
		if err != nil {
			w.Header().Set("WWW-Authenticate", `Bearer realm="musicstream", error="invalid_token"`)
			WriteError(w, http.StatusUnauthorized, "invalid_token", "access token invalid or expired")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxUserID, uid)))
	})
}

// IPLimiter is a per-client token bucket used for the login endpoints to
// slow down password guessing from the internet.
type IPLimiter struct {
	mu       sync.Mutex
	visitors map[string]*visitor
	every    rate.Limit
	burst    int
}

type visitor struct {
	lim  *rate.Limiter
	seen time.Time
}

func NewIPLimiter(every time.Duration, burst int) *IPLimiter {
	return &IPLimiter{visitors: map[string]*visitor{}, every: rate.Every(every), burst: burst}
}

func (l *IPLimiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if len(l.visitors) > 10000 { // bound memory
		for k, v := range l.visitors {
			if now.Sub(v.seen) > time.Hour {
				delete(l.visitors, k)
			}
		}
	}
	v, ok := l.visitors[ip]
	if !ok {
		v = &visitor{lim: rate.NewLimiter(l.every, l.burst)}
		l.visitors[ip] = v
	}
	v.seen = now
	return v.lim.Allow()
}

func (l *IPLimiter) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !l.allow(ClientIP(r)) {
			w.Header().Set("Retry-After", "60")
			WriteError(w, http.StatusTooManyRequests, "rate_limited", "too many attempts, try again later")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// clientIP uses the socket address; X-Forwarded-For is ignored because the
// server is exposed directly (no trusted proxy).
func ClientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
