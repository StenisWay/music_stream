// Package application builds the connection code the user pastes into the
// app.
package application

import (
	"encoding/base64"

	"musicstream/internal/feature/pairing/domain"
)

// LANAddressProvider lists the addresses of this machine that phones can
// reach (home LAN and VPN).
type LANAddressProvider interface {
	LANAddresses() ([]string, error)
}

// PairingConfig is the static part of the connection code.
type PairingConfig struct {
	Port      int
	Scheme    string
	CertPin   string
	PublicKey []byte // DER SPKI of the TLS certificate; nil without TLS
}

// Pairing builds the connection code.
type Pairing struct {
	cfg PairingConfig
	lan LANAddressProvider
}

func NewPairing(cfg PairingConfig, lan LANAddressProvider) *Pairing {
	return &Pairing{cfg: cfg, lan: lan}
}

// Info lists the LAN and VPN addresses of this machine. Away from home the
// phone connects to the VPN first, so no public address is needed.
func (p *Pairing) Info() domain.PairingInfo {
	info := domain.PairingInfo{
		Version: 1, Port: p.cfg.Port, Scheme: p.cfg.Scheme, CertPin: p.cfg.CertPin,
	}
	if p.cfg.PublicKey != nil {
		info.PublicKey = base64.StdEncoding.EncodeToString(p.cfg.PublicKey)
	}
	if lan, err := p.lan.LANAddresses(); err == nil {
		info.Hosts = append(info.Hosts, lan...)
	}
	return info
}
