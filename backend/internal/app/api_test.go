package app

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"musicstream/internal/config"
	authinfra "musicstream/internal/feature/auth/infrastructure"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newServer wires the real feature stack (via New) against a temp library
// containing one fake MP3 (an MPEG frame header followed by padding).
func newServer(t *testing.T) *httptest.Server {
	t.Helper()
	ctx := context.Background()
	dir := t.TempDir()
	lib := filepath.Join(dir, "library", "Artist")
	if err := os.MkdirAll(lib, 0o755); err != nil {
		t.Fatal(err)
	}
	frame := append([]byte{0xFF, 0xFB, 0x90, 0x64}, make([]byte, 4096)...)
	if err := os.WriteFile(filepath.Join(lib, "Song.mp3"), frame, 0o644); err != nil {
		t.Fatal(err)
	}
	hash, _ := authinfra.Bcrypt{Cost: 4}.Hash("password1")
	cfg := config.Default()
	cfg.DataDir = filepath.Join(dir, "data")
	cfg.Library.Root = filepath.Join(dir, "library")
	cfg.Library.Inbox = filepath.Join(dir, "inbox")
	cfg.Auth.PasswordHash = hash
	cfg.Server.TLS = config.TLSOff

	a, err := New(ctx, cfg, "test", slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(a.Close)
	if _, err := a.scanner.FullScan(ctx); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(a.handler)
	t.Cleanup(srv.Close)
	return srv
}

func login(t *testing.T, srv *httptest.Server) string {
	t.Helper()
	resp, err := http.Post(srv.URL+"/api/v1/auth/login", "application/json",
		strings.NewReader(`{"username":"admin","password":"password1","deviceName":"test"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("login status %d", resp.StatusCode)
	}
	var body struct{ AccessToken string }
	_ = json.NewDecoder(resp.Body).Decode(&body)
	return body.AccessToken
}

func get(t *testing.T, srv *httptest.Server, token, path string, hdr map[string]string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, srv.URL+path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func TestRequiresAuth(t *testing.T) {
	srv := newServer(t)
	if r := get(t, srv, "", "/api/v1/tracks", nil); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status %d", r.StatusCode)
	}
	if r := get(t, srv, "forged", "/api/v1/tracks", nil); r.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status %d", r.StatusCode)
	}
	if r := get(t, srv, "", "/healthz", nil); r.StatusCode != http.StatusOK {
		t.Fatalf("healthz status %d", r.StatusCode)
	}
}

func TestListAndRangeStream(t *testing.T) {
	srv := newServer(t)
	tok := login(t, srv)

	r := get(t, srv, tok, "/api/v1/tracks", nil)
	var page struct {
		Total int
		Items []struct {
			ID        int64
			Title     string
			Artist    string
			StreamURL string
			Playable  bool
		}
	}
	if err := json.NewDecoder(r.Body).Decode(&page); err != nil {
		t.Fatal(err)
	}
	if page.Total != 1 || page.Items[0].Title != "Song" || page.Items[0].Artist != "Artist" || !page.Items[0].Playable {
		t.Fatalf("unexpected page: %+v", page)
	}

	r = get(t, srv, tok, page.Items[0].StreamURL, map[string]string{"Range": "bytes=0-9"})
	body, _ := io.ReadAll(r.Body)
	if r.StatusCode != http.StatusPartialContent || len(body) != 10 || r.Header.Get("Content-Type") != "audio/mpeg" {
		t.Fatalf("range: status=%d len=%d type=%s", r.StatusCode, len(body), r.Header.Get("Content-Type"))
	}
}

func TestErrorFormat(t *testing.T) {
	srv := newServer(t)
	tok := login(t, srv)
	r := get(t, srv, tok, "/api/v1/tracks/999", nil)
	var e struct{ Error struct{ Code string } }
	_ = json.NewDecoder(r.Body).Decode(&e)
	if r.StatusCode != http.StatusNotFound || e.Error.Code != "not_found" {
		t.Fatalf("status=%d code=%s", r.StatusCode, e.Error.Code)
	}
	r = get(t, srv, tok, "/api/v1/tracks?sort=bogus", nil)
	if r.StatusCode != http.StatusBadRequest {
		t.Fatalf("bad sort status %d", r.StatusCode)
	}
}
