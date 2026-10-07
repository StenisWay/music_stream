// Package infrastructure implements the endpoint ports: public/LAN address
// discovery and the Firebase store.
//
// The Firebase store publishes the server endpoint to Firebase Realtime
// Database through its REST API. Only the address is stored there; audio
// never goes through Firebase, so the free Spark plan is plenty.
//
// The record is readable by anyone who knows the server id, so it is stored
// encrypted and signed (see platform/sealx): Firebase and anyone reading the
// node only see ciphertext.
package infrastructure

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"musicstream/internal/feature/endpoint/domain"
	"musicstream/internal/platform/sealx"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

var scopes = []string{
	"https://www.googleapis.com/auth/userinfo.email",
	"https://www.googleapis.com/auth/firebase.database",
}

// RTDB implements application.Store. It authenticates with a service account
// (admin privileges, bypasses security rules) and writes a sealed record to
// /servers/<serverId>.
type RTDB struct {
	baseURL string
	tokens  oauth2.TokenSource
	client  *http.Client
	sealer  *sealx.Sealer
}

// New creates the client. credentialsFile is the service-account JSON
// downloaded from the Firebase console.
func NewRTDB(ctx context.Context, databaseURL, credentialsFile string, sealer *sealx.Sealer) (*RTDB, error) {
	raw, err := os.ReadFile(credentialsFile)
	if err != nil {
		return nil, fmt.Errorf("read firebase credentials: %w", err)
	}
	creds, err := google.CredentialsFromJSON(ctx, raw, scopes...)
	if err != nil {
		return nil, fmt.Errorf("parse firebase credentials: %w", err)
	}
	return &RTDB{
		baseURL: strings.TrimRight(databaseURL, "/"),
		tokens:  creds.TokenSource,
		client:  &http.Client{Timeout: 15 * time.Second},
		sealer:  sealer,
	}, nil
}

// record is the plaintext inside the sealed envelope. Keep field names
// stable: the Android app decodes them after decrypting.
type record struct {
	PublicHost string   `json:"publicHost"`
	LANHosts   []string `json:"lanHosts"`
	Port       int      `json:"port"`
	Scheme     string   `json:"scheme"`
	CertPin    string   `json:"certPin"`
	UpdatedAt  int64    `json:"updatedAt"` // unix millis
}

func (r *RTDB) Publish(ctx context.Context, e domain.Endpoint) error {
	tok, err := r.tokens.Token()
	if err != nil {
		return fmt.Errorf("firebase token: %w", err)
	}
	plain, err := json.Marshal(record{
		PublicHost: e.PublicHost, LANHosts: e.LANHosts, Port: e.Port, Scheme: e.Scheme,
		CertPin: e.CertPin, UpdatedAt: e.UpdatedAt.UnixMilli(),
	})
	if err != nil {
		return err
	}
	env, err := r.sealer.Seal(e.ServerID, plain)
	if err != nil {
		return fmt.Errorf("seal endpoint: %w", err)
	}
	body, err := json.Marshal(env)
	if err != nil {
		return err
	}
	u := fmt.Sprintf("%s/servers/%s.json?access_token=%s", r.baseURL, url.PathEscape(e.ServerID), url.QueryEscape(tok.AccessToken))
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, u, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := r.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("firebase status %d: %s", resp.StatusCode, msg)
	}
	return nil
}

// NoopStore is used when Firebase is not configured (LAN / Tailscale only).
type NoopStore struct{}

func (NoopStore) Publish(context.Context, domain.Endpoint) error { return nil }
