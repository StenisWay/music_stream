package application

import (
	"context"
	"encoding/base64"
	"time"

	"musicstream/internal/feature/endpoint/domain"
)

// PairingConfig is the static part of the connection code.
type PairingConfig struct {
	ServerID    string
	FirebaseURL string
	Port        int
	Scheme      string
	CertPin     string
	PublicKey   []byte // DER SPKI of the TLS certificate; nil without TLS
	EndpointKey []byte // AES key of the Firebase record; nil when not published
}

// Pairing builds the connection code the user pastes into the app.
type Pairing struct {
	cfg       PairingConfig
	lan       LANAddressProvider
	publisher *Publisher // nil when endpoint publishing is disabled
}

func NewPairing(cfg PairingConfig, lan LANAddressProvider, publisher *Publisher) *Pairing {
	return &Pairing{cfg: cfg, lan: lan, publisher: publisher}
}

// Info lists every way to reach the server: LAN addresses first, then the
// public address when it can be detected. The Firebase rendezvous details
// are only included when the record is actually published.
func (p *Pairing) Info(ctx context.Context) domain.PairingInfo {
	info := domain.PairingInfo{
		Version: 1, Port: p.cfg.Port, Scheme: p.cfg.Scheme, CertPin: p.cfg.CertPin,
	}
	if p.cfg.PublicKey != nil {
		info.PublicKey = base64.StdEncoding.EncodeToString(p.cfg.PublicKey)
	}
	if p.cfg.EndpointKey != nil {
		info.ServerID, info.FirebaseURL = p.cfg.ServerID, p.cfg.FirebaseURL
		info.EndpointKey = base64.StdEncoding.EncodeToString(p.cfg.EndpointKey)
	}
	if lan, err := p.lan.LANAddresses(); err == nil {
		info.Hosts = append(info.Hosts, lan...)
	}
	if p.publisher != nil {
		dctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		if e, err := p.publisher.Detect(dctx); err == nil {
			info.Hosts = append(info.Hosts, e.PublicHost)
		}
	}
	return info
}
