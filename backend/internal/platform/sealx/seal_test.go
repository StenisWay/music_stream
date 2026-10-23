package sealx

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"testing"
)

func newTestSealer(t *testing.T) (*Sealer, []byte, *ecdsa.PrivateKey) {
	t.Helper()
	key := bytes.Repeat([]byte{7}, KeySize)
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(key, priv)
	if err != nil {
		t.Fatal(err)
	}
	return s, key, priv
}

func TestSealOpenRoundTrip(t *testing.T) {
	s, key, priv := newTestSealer(t)
	msg := []byte(`{"publicHost":"36.1.2.3"}`)
	env, err := s.Seal("server-a", msg)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains([]byte(env.CT), []byte("36.1.2.3")) {
		t.Fatal("ciphertext leaks the address")
	}
	got, err := Open(key, &priv.PublicKey, "server-a", env)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatalf("got %q", got)
	}
}

func TestOpenRejectsTampering(t *testing.T) {
	s, key, priv := newTestSealer(t)
	env, err := s.Seal("server-a", []byte("hello"))
	if err != nil {
		t.Fatal(err)
	}
	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)

	ct, _ := base64.StdEncoding.DecodeString(env.CT)
	ct[0] ^= 1
	flipped := env
	flipped.CT = base64.StdEncoding.EncodeToString(ct)

	cases := map[string]func() error{
		"wrong context": func() error { _, err := Open(key, &priv.PublicKey, "server-b", env); return err },
		"wrong signer":  func() error { _, err := Open(key, &other.PublicKey, "server-a", env); return err },
		"wrong key": func() error {
			_, err := Open(bytes.Repeat([]byte{8}, KeySize), &priv.PublicKey, "server-a", env)
			return err
		},
		"flipped ct":      func() error { _, err := Open(key, &priv.PublicKey, "server-a", flipped); return err },
		"unknown version": func() error { e := env; e.V = 1; _, err := Open(key, &priv.PublicKey, "server-a", e); return err },
	}
	for name, open := range cases {
		if open() == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestNewValidatesInputs(t *testing.T) {
	priv, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if _, err := New(make([]byte, KeySize), priv); err == nil {
		t.Error("P-384 signer accepted")
	}
	p256, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if _, err := New(make([]byte, 16), p256); err == nil {
		t.Error("short key accepted")
	}
}
