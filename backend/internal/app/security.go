package app

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"log/slog"
	"path/filepath"

	"musicstream/internal/config"
	"musicstream/internal/platform/sqlitex"
	"musicstream/internal/platform/tlsx"
)

const settingJWTSecret = "jwt_secret"

// security is the server's identity and key material: TLS certificate,
// and the persistent secrets that features are configured with.
type security struct {
	cert      *tls.Certificate // nil when TLS is off
	certPin   string
	publicKey []byte // DER SPKI of cert, handed to the app at pairing
	jwtSecret []byte
}

func loadSecurity(ctx context.Context, cfg config.Config, db *sqlitex.DB, log *slog.Logger) (security, error) {
	var s security
	if err := s.loadTLS(cfg, log); err != nil {
		return s, err
	}
	var err error
	s.jwtSecret, err = persistentSecret(ctx, db, settingJWTSecret, 32)
	return s, err
}

func (s *security) loadTLS(cfg config.Config, log *slog.Logger) error {
	var cert tls.Certificate
	var err error
	switch cfg.Server.TLS {
	case config.TLSOff:
		log.Warn("TLS is disabled: credentials travel in clear text; only use this on a trusted LAN or behind the VPN")
		return nil
	case config.TLSFiles:
		cert, err = tls.LoadX509KeyPair(cfg.Server.CertFile, cfg.Server.KeyFile)
	default:
		cert, err = tlsx.LoadOrCreateSelfSigned(filepath.Join(cfg.DataDir, "tls"))
	}
	if err != nil {
		return fmt.Errorf("tls: %w", err)
	}
	pin, err := tlsx.SPKIPin(cert)
	if err != nil {
		return err
	}
	spki, err := tlsx.SPKI(cert)
	if err != nil {
		return err
	}
	s.cert, s.certPin, s.publicKey = &cert, pin, spki
	return nil
}

func (s *security) scheme() string {
	if s.cert == nil {
		return "http"
	}
	return "https"
}

// persistentSecret returns a random value stored in the settings table,
// creating it on first use.
func persistentSecret(ctx context.Context, db *sqlitex.DB, key string, size int) ([]byte, error) {
	if v, ok, err := db.GetSetting(ctx, key); err != nil {
		return nil, err
	} else if ok {
		return hex.DecodeString(v)
	}
	b := make([]byte, size)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	return b, db.PutSetting(ctx, key, hex.EncodeToString(b))
}
