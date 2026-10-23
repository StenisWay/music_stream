package domain

import (
	"encoding/base64"
	"encoding/json"
)

// PairingInfo is everything the Android app needs to find and trust this
// server. It is shown as a "connection code" the user pastes into the app.
type PairingInfo struct {
	Version int      `json:"v"`
	Hosts   []string `json:"hosts"` // LAN and VPN addresses of this machine
	Port    int      `json:"port"`
	Scheme  string   `json:"scheme"`
	CertPin string   `json:"pin,omitempty"`
	// PublicKey is the server's DER SubjectPublicKeyInfo (base64); the app
	// checks that it hashes to CertPin.
	PublicKey string `json:"pk,omitempty"`
}

const pairingPrefix = "MS1:"

// Encode renders the connection code ("MS1:<base64url JSON>").
func (p PairingInfo) Encode() string {
	raw, _ := json.Marshal(p)
	return pairingPrefix + base64.RawURLEncoding.EncodeToString(raw)
}
