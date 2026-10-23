// Package domain holds the auth entities: accounts and rotating refresh
// tokens, plus the repository that persists them.
package domain

import (
	"musicstream/internal/shared"
	"time"
)

// User is an account that may access the server.
type User struct {
	ID           shared.UserID
	Username     string
	PasswordHash string
	CreatedAt    time.Time
}

// RefreshToken is a long-lived, single-use credential stored only as a hash.
type RefreshToken struct {
	ID         string // random identifier, also the token's lookup key
	UserID     shared.UserID
	TokenHash  string
	DeviceName string
	ExpiresAt  time.Time
	CreatedAt  time.Time
	RevokedAt  *time.Time
}

func (t RefreshToken) Usable(now time.Time) bool {
	return t.RevokedAt == nil && now.Before(t.ExpiresAt)
}
