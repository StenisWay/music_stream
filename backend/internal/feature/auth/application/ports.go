package application

import (
	"musicstream/internal/shared"
	"time"
)

// PasswordHasher verifies passwords against stored hashes.
type PasswordHasher interface {
	Hash(password string) (string, error)
	Compare(hash, password string) error
}

// AccessTokenIssuer creates and validates short-lived access tokens.
type AccessTokenIssuer interface {
	Issue(user shared.UserID, ttl time.Duration) (token string, expiresAt time.Time, err error)
	Verify(token string) (shared.UserID, error)
}
