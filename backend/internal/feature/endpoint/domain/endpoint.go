// Package domain holds the reachability model: where clients can find this
// server, and the connection code that lets the app trust it.
package domain

import (
	"net/netip"
	"time"
)

// Endpoint describes how clients can reach this server. It is published to
// a rendezvous store (Firebase) because the home connection has a dynamic IP.
type Endpoint struct {
	ServerID   string
	PublicHost string   // public IPv4 as seen from the internet
	LANHosts   []string // private addresses, tried first when on home Wi-Fi
	Port       int
	Scheme     string // "https" or "http"
	CertPin    string // "sha256/<base64>" SPKI pin of the TLS certificate
	UpdatedAt  time.Time
}

// SameAddress reports whether two endpoints would lead clients to the same
// place (used to avoid needless publishing).
func (e Endpoint) SameAddress(o Endpoint) bool {
	if e.PublicHost != o.PublicHost || e.Port != o.Port || e.Scheme != o.Scheme || e.CertPin != o.CertPin || len(e.LANHosts) != len(o.LANHosts) {
		return false
	}
	for i := range e.LANHosts {
		if e.LANHosts[i] != o.LANHosts[i] {
			return false
		}
	}
	return true
}

var cgnat = netip.MustParsePrefix("100.64.0.0/10")

// IsCGNAT reports whether addr is in the carrier-grade NAT range 100.64/10.
// Such a public address cannot be port-forwarded.
func IsCGNAT(addr netip.Addr) bool { return cgnat.Contains(addr) }
