// Package application holds the auth use cases: login, access tokens and
// rotating refresh tokens.
package application

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"musicstream/internal/feature/auth/domain"
	"musicstream/internal/shared"
	"strings"
	"time"
)

// TokenPair is returned on login and refresh.
type TokenPair struct {
	AccessToken      string
	AccessExpiresAt  time.Time
	RefreshToken     string
	RefreshExpiresAt time.Time
}

// Config tunes token lifetimes.
type Config struct {
	AccessTTL  time.Duration
	RefreshTTL time.Duration
}

// Service implements authentication. Refresh tokens are opaque
// "<id>.<secret>" strings; only a SHA-256 of the secret is stored, and each
// token is single-use (rotation). Re-use of a revoked token revokes every
// session of the user, which limits the damage of a stolen token.
type Service struct {
	users  domain.UserRepository
	hasher PasswordHasher
	issuer AccessTokenIssuer
	cfg    Config
	now    func() time.Time
	// dummyHash is compared against when the user does not exist so that
	// both paths cost one bcrypt comparison (no user enumeration by timing).
	dummyHash string
}

func NewService(users domain.UserRepository, hasher PasswordHasher, issuer AccessTokenIssuer, cfg Config) *Service {
	dummy, _ := hasher.Hash("timing-equaliser")
	return &Service{users: users, hasher: hasher, issuer: issuer, cfg: cfg, now: time.Now, dummyHash: dummy}
}

// EnsureUser creates or updates the configured account at start-up.
func (s *Service) EnsureUser(ctx context.Context, username, passwordHash string) error {
	if username == "" || passwordHash == "" {
		return fmt.Errorf("username and password hash are required: %w", shared.ErrInvalidArgument)
	}
	u, err := s.users.GetByUsername(ctx, username)
	switch {
	case errors.Is(err, shared.ErrNotFound):
		u = domain.User{Username: username, CreatedAt: s.now()}
	case err != nil:
		return err
	}
	if u.PasswordHash == passwordHash {
		return nil
	}
	u.PasswordHash = passwordHash
	if _, err := s.users.Upsert(ctx, u); err != nil {
		return err
	}
	// Password changed: invalidate existing sessions.
	if u.ID != 0 {
		return s.users.RevokeAllRefreshTokens(ctx, u.ID, s.now())
	}
	return nil
}

func (s *Service) Login(ctx context.Context, username, password, device string) (TokenPair, error) {
	u, err := s.users.GetByUsername(ctx, strings.TrimSpace(username))
	if err != nil {
		if errors.Is(err, shared.ErrNotFound) {
			_ = s.hasher.Compare(s.dummyHash, password)
			return TokenPair{}, shared.ErrUnauthenticated
		}
		return TokenPair{}, err
	}
	if err := s.hasher.Compare(u.PasswordHash, password); err != nil {
		return TokenPair{}, shared.ErrUnauthenticated
	}
	return s.issuePair(ctx, u.ID, device)
}

func (s *Service) Refresh(ctx context.Context, refreshToken string) (TokenPair, error) {
	id, secret, ok := strings.Cut(refreshToken, ".")
	if !ok || id == "" || secret == "" {
		return TokenPair{}, shared.ErrUnauthenticated
	}
	stored, err := s.users.GetRefreshToken(ctx, id)
	if err != nil {
		if errors.Is(err, shared.ErrNotFound) {
			return TokenPair{}, shared.ErrUnauthenticated
		}
		return TokenPair{}, err
	}
	if subtle.ConstantTimeCompare([]byte(stored.TokenHash), []byte(hashSecret(secret))) != 1 {
		return TokenPair{}, shared.ErrUnauthenticated
	}
	now := s.now()
	if stored.RevokedAt != nil {
		// Replay of a rotated token: assume theft, end all sessions.
		_ = s.users.RevokeAllRefreshTokens(ctx, stored.UserID, now)
		return TokenPair{}, shared.ErrUnauthenticated
	}
	if !stored.Usable(now) {
		return TokenPair{}, shared.ErrUnauthenticated
	}
	if err := s.users.RevokeRefreshToken(ctx, stored.ID, now); err != nil {
		return TokenPair{}, err
	}
	return s.issuePair(ctx, stored.UserID, stored.DeviceName)
}

func (s *Service) Logout(ctx context.Context, refreshToken string) error {
	id, _, ok := strings.Cut(refreshToken, ".")
	if !ok {
		return nil
	}
	err := s.users.RevokeRefreshToken(ctx, id, s.now())
	if errors.Is(err, shared.ErrNotFound) {
		return nil
	}
	return err
}

// Authenticate validates an access token.
func (s *Service) Authenticate(token string) (shared.UserID, error) {
	id, err := s.issuer.Verify(token)
	if err != nil {
		return 0, shared.ErrUnauthenticated
	}
	return id, nil
}

func (s *Service) issuePair(ctx context.Context, user shared.UserID, device string) (TokenPair, error) {
	access, accessExp, err := s.issuer.Issue(user, s.cfg.AccessTTL)
	if err != nil {
		return TokenPair{}, err
	}
	id, err := randomString(12)
	if err != nil {
		return TokenPair{}, err
	}
	secret, err := randomString(32)
	if err != nil {
		return TokenPair{}, err
	}
	now := s.now()
	rt := domain.RefreshToken{
		ID: id, UserID: user, TokenHash: hashSecret(secret), DeviceName: truncate(device, 100),
		ExpiresAt: now.Add(s.cfg.RefreshTTL), CreatedAt: now,
	}
	if err := s.users.SaveRefreshToken(ctx, rt); err != nil {
		return TokenPair{}, err
	}
	return TokenPair{
		AccessToken: access, AccessExpiresAt: accessExp,
		RefreshToken: id + "." + secret, RefreshExpiresAt: rt.ExpiresAt,
	}, nil
}

func hashSecret(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}

func randomString(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func truncate(s string, n int) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) > n {
		r = r[:n]
	}
	return string(r)
}
