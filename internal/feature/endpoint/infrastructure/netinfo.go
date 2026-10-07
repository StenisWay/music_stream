package infrastructure

import (
	"context"
	"errors"
	"fmt"
	"io"
	"musicstream/internal/feature/endpoint/domain"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

// PublicIP implements application.PublicIPResolver by asking several echo
// services, returning the first valid public IPv4 answer.
type PublicIP struct {
	Services []string
	Client   *http.Client
}

func NewPublicIP() *PublicIP {
	return &PublicIP{
		Services: []string{"https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"},
		Client:   &http.Client{Timeout: 10 * time.Second},
	}
}

func (p *PublicIP) PublicIP(ctx context.Context) (string, error) {
	var errs []error
	for _, svc := range p.Services {
		ip, err := p.query(ctx, svc)
		if err == nil {
			return ip, nil
		}
		errs = append(errs, fmt.Errorf("%s: %w", svc, err))
	}
	return "", errors.Join(errs...)
}

func (p *PublicIP) query(ctx context.Context, url string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "curl/8") // some services return HTML otherwise
	resp, err := p.Client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64))
	if err != nil {
		return "", err
	}
	addr, err := netip.ParseAddr(strings.TrimSpace(string(body)))
	if err != nil || !addr.Is4() {
		return "", fmt.Errorf("unexpected answer %q", body)
	}
	return addr.String(), nil
}

// LAN implements application.LANAddressProvider.
type LAN struct{}

// LANAddresses returns private IPv4 addresses of up, non-loopback interfaces
// (Tailscale's 100.x addresses are included: clients can reach them too).
func (LAN) LANAddresses() ([]string, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	var out []string
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || strings.HasPrefix(iface.Name, "docker") || strings.HasPrefix(iface.Name, "br-") || strings.HasPrefix(iface.Name, "veth") {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			prefix, err := netip.ParsePrefix(a.String())
			if err != nil {
				continue
			}
			ip := prefix.Addr()
			if ip.Is4() && (ip.IsPrivate() || domain.IsCGNAT(ip)) {
				out = append(out, ip.String())
			}
		}
	}
	return out, nil
}
