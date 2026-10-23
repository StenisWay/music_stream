package app

import (
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"

	"musicstream/internal/config"
	"musicstream/internal/platform/sealx"
	"musicstream/internal/platform/sqlitex"
	"musicstream/internal/platform/tlsx"
)

const (
	settingJWTSecret = "jwt_secret"
	settingServerID  = "server_id"
	// settingEndpointKey is the AES-256 key that encrypts the Firebase
	// record. It reaches the app only inside the connection code.
	settingEndpointKey = "endpoint_key"
)

// security is the server's identity and key material: TLS certificate,
// server id and the persistent secrets that features are configured with.
type security struct {
	cert      *tls.Certificate // nil when TLS is off
	certPin   string
	publicKey []byte // DER SPKI of cert, handed to the app at pairing
	serverID  string
	jwtSecret []byte
	// endpointKey is set when the Firebase record is published (sealed).
	endpointKey []byte
}

func loadSecurity(ctx context.Context, cfg config.Config, db *sqlitex.DB, log *slog.Logger) (security, error) {
	var s security
	s.serverID = cfg.Endpoint.ServerID
	if s.serverID == "" {
		// An unguessable id doubles as the read capability for the Firebase record.
		id, err := persistentSecret(ctx, db, settingServerID, 16)
		if err != nil {
			return s, err
		}
		s.serverID = hex.EncodeToString(id)
	}
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
		log.Warn("TLS is disabled: credentials travel in clear text; only use this on a trusted LAN or behind Tailscale")
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

// endpointSealer encrypts the Firebase record with the endpoint key and
// signs it with the TLS private key, whose public key the app already holds.
func (s *security) endpointSealer(ctx context.Context, db *sqlitex.DB) (*sealx.Sealer, error) {
	if s.cert == nil {
		return nil, errors.New("firebase publishing needs TLS: the record is signed with the server private key")
	}
	signer, ok := s.cert.PrivateKey.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("firebase publishing needs an ECDSA P-256 server key (use tls: self-signed)")
	}
	key, err := persistentSecret(ctx, db, settingEndpointKey, sealx.KeySize)
	if err != nil {
		return nil, err
	}
	sealer, err := sealx.New(key, signer)
	if err != nil {
		return nil, err
	}
	s.endpointKey = key
	return sealer, nil
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
