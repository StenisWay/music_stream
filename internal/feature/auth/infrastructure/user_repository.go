// Package infrastructure implements the auth ports: the SQLite user
// repository, bcrypt password hashing and JWT access tokens.
package infrastructure

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"
	"musicstream/internal/feature/auth/domain"
	"musicstream/internal/platform/sqlitex"
	"musicstream/internal/shared"
	"time"
)

//go:embed migrations/*.sql
var migrations embed.FS

// UserRepository implements domain.UserRepository on SQLite.
type UserRepository struct{ db *sqlitex.DB }

func NewUserRepository(db *sqlitex.DB) *UserRepository { return &UserRepository{db: db} }

// Migrate creates/updates the users and refresh_tokens schema.
func Migrate(ctx context.Context, db *sqlitex.DB) error {
	sub, _ := fs.Sub(migrations, "migrations")
	return db.Migrate(ctx, "auth", sub)
}

func scanUser(r sqlitex.RowScanner) (domain.User, error) {
	var u domain.User
	var created int64
	err := r.Scan(&u.ID, &u.Username, &u.PasswordHash, &created)
	u.CreatedAt = sqlitex.FromUnixNano(created)
	return u, err
}

func (s *UserRepository) GetByUsername(ctx context.Context, username string) (domain.User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx, `SELECT id, username, password_hash, created_at FROM users WHERE username = ?`, username))
	return u, sqlitex.NotFound(err, "user")
}

func (s *UserRepository) Get(ctx context.Context, id shared.UserID) (domain.User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx, `SELECT id, username, password_hash, created_at FROM users WHERE id = ?`, id))
	return u, sqlitex.NotFound(err, "user")
}

func (s *UserRepository) Upsert(ctx context.Context, u domain.User) (domain.User, error) {
	if u.ID == 0 {
		res, err := s.db.ExecContext(ctx, `INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)`,
			u.Username, u.PasswordHash, sqlitex.UnixNano(u.CreatedAt))
		if err != nil {
			return domain.User{}, err
		}
		id, err := res.LastInsertId()
		u.ID = shared.UserID(id)
		return u, err
	}
	_, err := s.db.ExecContext(ctx, `UPDATE users SET username = ?, password_hash = ? WHERE id = ?`, u.Username, u.PasswordHash, u.ID)
	return u, err
}

func (s *UserRepository) SaveRefreshToken(ctx context.Context, t domain.RefreshToken) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO refresh_tokens (id, user_id, token_hash, device_name, expires_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?)`, t.ID, t.UserID, t.TokenHash, t.DeviceName, sqlitex.UnixNano(t.ExpiresAt), sqlitex.UnixNano(t.CreatedAt))
	return err
}

func (s *UserRepository) GetRefreshToken(ctx context.Context, id string) (domain.RefreshToken, error) {
	var t domain.RefreshToken
	var expires, created int64
	var revoked sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT id, user_id, token_hash, device_name, expires_at, created_at, revoked_at
		FROM refresh_tokens WHERE id = ?`, id).Scan(&t.ID, &t.UserID, &t.TokenHash, &t.DeviceName, &expires, &created, &revoked)
	if err != nil {
		return domain.RefreshToken{}, sqlitex.NotFound(err, "refresh token")
	}
	t.ExpiresAt, t.CreatedAt = sqlitex.FromUnixNano(expires), sqlitex.FromUnixNano(created)
	if revoked.Valid {
		at := sqlitex.FromUnixNano(revoked.Int64)
		t.RevokedAt = &at
	}
	return t, nil
}

func (s *UserRepository) RevokeRefreshToken(ctx context.Context, id string, at time.Time) error {
	res, err := s.db.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, sqlitex.UnixNano(at), id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return fmt.Errorf("refresh token: %w", shared.ErrNotFound)
	}
	return nil
}

func (s *UserRepository) RevokeAllRefreshTokens(ctx context.Context, user shared.UserID, at time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, sqlitex.UnixNano(at), user)
	if err != nil {
		return err
	}
	// Housekeeping: drop tokens that expired long ago.
	_, err = s.db.ExecContext(ctx, `DELETE FROM refresh_tokens WHERE expires_at < ?`, sqlitex.UnixNano(at.Add(-30*24*time.Hour)))
	return err
}
