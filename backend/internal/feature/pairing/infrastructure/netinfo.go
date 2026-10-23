// Package infrastructure discovers the network addresses of this machine.
package infrastructure

import (
	"net"
	"net/netip"
	"strings"

	"musicstream/internal/feature/pairing/domain"
)

// LAN implements application.LANAddressProvider.
type LAN struct{}

// LANAddresses returns the reachable IPv4 addresses (see domain.Reachable)
// of up, non-loopback interfaces, including VPN interfaces such as
// tailscale0 and wg0. Container bridges are skipped.
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
			if ip := prefix.Addr(); domain.Reachable(ip) {
				out = append(out, ip.String())
			}
		}
	}
	return out, nil
}
