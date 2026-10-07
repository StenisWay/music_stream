package domain

import (
	"encoding/base64"
	"encoding/json"
)

// PairingInfo is everything the Android app needs to find and trust this
// server. It is shown as a "connection code" the user pastes into the app.
type PairingInfo struct {
	Version     int      `json:"v"`
	ServerID    string   `json:"sid,omitempty"`
	FirebaseURL string   `json:"fb,omitempty"`
	Hosts       []string `json:"hosts"` // LAN first, then public
	Port        int      `json:"port"`
	Scheme      string   `json:"scheme"`
	CertPin     string   `json:"pin,omitempty"`
	// PublicKey is the server's DER SubjectPublicKeyInfo (base64). The app
	// checks it against CertPin and uses it to verify the Firebase record.
	PublicKey string `json:"pk,omitempty"`
	// EndpointKey is the AES-256 key (base64) that decrypts the Firebase
	// record. Treat the connection code like a password.
	EndpointKey string `json:"ek,omitempty"`
}

const pairingPrefix = "MS1:"

// Encode renders the connection code ("MS1:<base64url JSON>").
func (p PairingInfo) Encode() string {
	raw, _ := json.Marshal(p)
	return pairingPrefix + base64.RawURLEncoding.EncodeToString(raw)
}
