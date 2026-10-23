// Package domain holds the pairing model: the connection code that lets the
// app find and trust this server, and which addresses belong in it.
package domain

import "net/netip"

// tailscale is the 100.64.0.0/10 range Tailscale assigns its addresses from.
var tailscale = netip.MustParsePrefix("100.64.0.0/10")

// Reachable reports whether a phone can reach addr on the home LAN or
// through the VPN: RFC 1918 private addresses (LAN, WireGuard) and the
// Tailscale range. Public addresses are not advertised: the server is not
// exposed to the internet.
func Reachable(addr netip.Addr) bool {
	return addr.Is4() && (addr.IsPrivate() || tailscale.Contains(addr))
}
