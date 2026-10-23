package domain

import (
	"context"
	"time"

	"musicstream/internal/shared"
)

// UserRepository persists accounts and refresh tokens.
type UserRepository interface {
	GetByUsername(ctx context.Context, username string) (User, error)
	Get(ctx context.Context, id shared.UserID) (User, error)
	Upsert(ctx context.Context, u User) (User, error)

	SaveRefreshToken(ctx context.Context, t RefreshToken) error
	GetRefreshToken(ctx context.Context, id string) (RefreshToken, error)
	RevokeRefreshToken(ctx context.Context, id string, at time.Time) error
	RevokeAllRefreshTokens(ctx context.Context, user shared.UserID, at time.Time) error
}
