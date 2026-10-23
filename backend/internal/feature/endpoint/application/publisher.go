// Package application holds the reachability use cases: keeping the
// rendezvous record (Firebase) pointing at the current public/LAN address
// and building the app connection code.
package application

import (
	"context"
	"fmt"
	"log/slog"
	"musicstream/internal/feature/endpoint/domain"
	"net/netip"
	"sort"
	"sync"
	"time"
)

// PublicIPResolver discovers the address the internet sees.
type PublicIPResolver interface {
	PublicIP(ctx context.Context) (string, error)
}

// LANAddressProvider lists private addresses of this machine.
type LANAddressProvider interface {
	LANAddresses() ([]string, error)
}

// Store persists the endpoint record somewhere clients can read it.
type Store interface {
	Publish(ctx context.Context, e domain.Endpoint) error
}

// Config is the static part of the endpoint.
type Config struct {
	ServerID string
	Port     int
	Scheme   string
	CertPin  string
	Interval time.Duration
}

// Publisher periodically detects address changes and publishes them.
type Publisher struct {
	ip    PublicIPResolver
	lan   LANAddressProvider
	store Store
	cfg   Config
	log   *slog.Logger
	now   func() time.Time

	mu   sync.Mutex
	last domain.Endpoint
}

func NewPublisher(ip PublicIPResolver, lan LANAddressProvider, store Store, cfg Config, log *slog.Logger) *Publisher {
	return &Publisher{ip: ip, lan: lan, store: store, cfg: cfg, log: log, now: time.Now}
}

// Current returns the last published endpoint.
func (p *Publisher) Current() domain.Endpoint {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.last
}

// Detect builds the endpoint for the current network situation.
func (p *Publisher) Detect(ctx context.Context) (domain.Endpoint, error) {
	public, err := p.ip.PublicIP(ctx)
	if err != nil {
		return domain.Endpoint{}, fmt.Errorf("detect public ip: %w", err)
	}
	lan, err := p.lan.LANAddresses()
	if err != nil {
		return domain.Endpoint{}, fmt.Errorf("list lan addresses: %w", err)
	}
	sort.Strings(lan)
	return domain.Endpoint{
		ServerID: p.cfg.ServerID, PublicHost: public, LANHosts: lan,
		Port: p.cfg.Port, Scheme: p.cfg.Scheme, CertPin: p.cfg.CertPin, UpdatedAt: p.now(),
	}, nil
}

// PublishIfChanged publishes only when the address changed, or once a day as
// a heartbeat so clients can tell the record is fresh.
func (p *Publisher) PublishIfChanged(ctx context.Context) (bool, error) {
	e, err := p.Detect(ctx)
	if err != nil {
		return false, err
	}
	p.mu.Lock()
	last := p.last
	p.mu.Unlock()
	if e.SameAddress(last) && e.UpdatedAt.Sub(last.UpdatedAt) < 24*time.Hour {
		return false, nil
	}
	if err := p.store.Publish(ctx, e); err != nil {
		return false, fmt.Errorf("publish endpoint: %w", err)
	}
	p.mu.Lock()
	p.last = e
	p.mu.Unlock()
	p.log.Info("endpoint published", "public", e.PublicHost, "lan", e.LANHosts, "port", e.Port)
	return true, nil
}

// Run publishes immediately and then on every interval until ctx ends.
func (p *Publisher) Run(ctx context.Context) {
	interval := p.cfg.Interval
	if interval <= 0 {
		interval = 5 * time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		if _, err := p.PublishIfChanged(ctx); err != nil && ctx.Err() == nil {
			p.log.Warn("endpoint publish failed", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// WarnIfCGNAT logs a warning when the public address is behind carrier-grade
// NAT, where port forwarding cannot work.
func (p *Publisher) WarnIfCGNAT(ctx context.Context) {
	dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	e, err := p.Detect(dctx)
	if err != nil {
		return
	}
	if addr, err := netip.ParseAddr(e.PublicHost); err == nil && domain.IsCGNAT(addr) {
		p.log.Warn("public address is in the CGNAT range; port forwarding will not work, use Tailscale", "ip", e.PublicHost)
	}
}
