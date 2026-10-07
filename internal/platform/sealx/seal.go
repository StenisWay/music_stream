// Package sealx encrypts and signs small records that are stored somewhere
// public (the Firebase endpoint record).
//
//   - Confidentiality: AES-256-GCM with a symmetric key that only reaches
//     the app through the connection code (never through Firebase).
//   - Authenticity: an ECDSA P-256 signature made with the server's TLS
//     private key; the app verifies it with the public key it got at
//     pairing. A phone that leaks the symmetric key still cannot forge a
//     record that redirects other clients.
//
// The format is mirrored by the Android app (EndpointRecordCipher.kt); keep
// both in sync.
package sealx

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
)

// KeySize is the AES-256 key length in bytes.
const KeySize = 32

const (
	version = 2
	// domain separates these signatures from the TLS handshake signatures
	// made with the same key.
	domain = "musicstream/endpoint/v2\x00"
)

// Envelope is the JSON document stored publicly. Byte fields are standard
// base64.
type Envelope struct {
	V     int    `json:"v"`
	Nonce string `json:"n"`
	CT    string `json:"ct"` // ciphertext || GCM tag
	Sig   string `json:"sig"`
}

// Sealer encrypts with a symmetric key and signs with the server key.
type Sealer struct {
	aead   cipher.AEAD
	signer *ecdsa.PrivateKey
}

// New creates a Sealer. key must be KeySize bytes; signer must be a P-256 key.
func New(key []byte, signer *ecdsa.PrivateKey) (*Sealer, error) {
	if len(key) != KeySize {
		return nil, fmt.Errorf("seal key must be %d bytes, got %d", KeySize, len(key))
	}
	if signer == nil || signer.Curve != elliptic.P256() {
		return nil, errors.New("seal signer must be an ECDSA P-256 key")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Sealer{aead: aead, signer: signer}, nil
}

// Seal encrypts plaintext bound to context (the record id, so a record
// cannot be copied to another path) and signs the result.
func (s *Sealer) Seal(context string, plaintext []byte) (Envelope, error) {
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return Envelope{}, err
	}
	ct := s.aead.Seal(nil, nonce, plaintext, aad(context))
	sig, err := ecdsa.SignASN1(rand.Reader, s.signer, digest(context, nonce, ct))
	if err != nil {
		return Envelope{}, fmt.Errorf("sign record: %w", err)
	}
	enc := base64.StdEncoding.EncodeToString
	return Envelope{V: version, Nonce: enc(nonce), CT: enc(ct), Sig: enc(sig)}, nil
}

// Open verifies and decrypts an envelope. The server never reads records
// back; this exists for tests and documents what clients do.
func Open(key []byte, pub *ecdsa.PublicKey, context string, e Envelope) ([]byte, error) {
	if e.V != version {
		return nil, fmt.Errorf("unsupported record version %d", e.V)
	}
	dec := base64.StdEncoding.DecodeString
	nonce, err1 := dec(e.Nonce)
	ct, err2 := dec(e.CT)
	sig, err3 := dec(e.Sig)
	if err := errors.Join(err1, err2, err3); err != nil {
		return nil, fmt.Errorf("decode record: %w", err)
	}
	if !ecdsa.VerifyASN1(pub, digest(context, nonce, ct), sig) {
		return nil, errors.New("record signature is invalid")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(nonce) != aead.NonceSize() {
		return nil, errors.New("invalid nonce")
	}
	return aead.Open(nil, nonce, ct, aad(context))
}

func aad(context string) []byte { return []byte(domain + context) }

// digest is SHA-256 over domain || context || 0x00 || nonce || ct, which is
// what Java's "SHA256withECDSA" computes over the same bytes.
func digest(context string, nonce, ct []byte) []byte {
	h := sha256.New()
	h.Write([]byte(domain + context + "\x00"))
	h.Write(nonce)
	h.Write(ct)
	return h.Sum(nil)
}
