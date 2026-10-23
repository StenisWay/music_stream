// Package infrastructure is the SQLite implementation of the playlist
// repository. Its tables reference the library's tracks table by foreign key.
package infrastructure

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"
	library "musicstream/internal/feature/library/domain"
	"musicstream/internal/feature/playlist/domain"
	"musicstream/internal/platform/sqlitex"
	"musicstream/internal/shared"
	"time"
)

//go:embed migrations/*.sql
var migrations embed.FS

// PlaylistRepository implements domain.Repository on SQLite.
type PlaylistRepository struct{ db *sqlitex.DB }

func NewPlaylistRepository(db *sqlitex.DB) *PlaylistRepository { return &PlaylistRepository{db: db} }

// Migrate creates/updates the playlist schema. Run after the library and
// auth migrations (foreign keys reference tracks and users).
func Migrate(ctx context.Context, db *sqlitex.DB) error {
	sub, _ := fs.Sub(migrations, "migrations")
	return db.Migrate(ctx, "playlist", sub)
}

func (s *PlaylistRepository) List(ctx context.Context, owner shared.UserID) ([]domain.PlaylistSummary, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT p.id, p.name, COUNT(pt.track_id), p.updated_at
		FROM playlists p LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
		WHERE p.owner_id = ? GROUP BY p.id ORDER BY p.name COLLATE NOCASE`, owner)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.PlaylistSummary
	for rows.Next() {
		var s domain.PlaylistSummary
		var updated int64
		if err := rows.Scan(&s.ID, &s.Name, &s.TrackCount, &updated); err != nil {
			return nil, err
		}
		s.UpdatedAt = sqlitex.FromUnixNano(updated)
		out = append(out, s)
	}
	return out, rows.Err()
}

func (s *PlaylistRepository) Get(ctx context.Context, owner shared.UserID, id domain.PlaylistID) (domain.Playlist, error) {
	var p domain.Playlist
	var created, updated int64
	err := s.db.QueryRowContext(ctx, `SELECT id, owner_id, name, created_at, updated_at FROM playlists WHERE id = ? AND owner_id = ?`,
		id, owner).Scan(&p.ID, &p.OwnerID, &p.Name, &created, &updated)
	if err != nil {
		return domain.Playlist{}, sqlitex.NotFound(err, fmt.Sprintf("playlist %d", id))
	}
	p.CreatedAt, p.UpdatedAt = sqlitex.FromUnixNano(created), sqlitex.FromUnixNano(updated)
	rows, err := s.db.QueryContext(ctx, `SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position`, id)
	if err != nil {
		return domain.Playlist{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var tid library.TrackID
		if err := rows.Scan(&tid); err != nil {
			return domain.Playlist{}, err
		}
		p.TrackIDs = append(p.TrackIDs, tid)
	}
	return p, rows.Err()
}

func (s *PlaylistRepository) Create(ctx context.Context, p domain.Playlist) (domain.Playlist, error) {
	now := time.Now()
	p.CreatedAt, p.UpdatedAt = now, now
	err := s.db.InTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `INSERT INTO playlists (owner_id, name, created_at, updated_at) VALUES (?, ?, ?, ?)`,
			p.OwnerID, p.Name, sqlitex.UnixNano(now), sqlitex.UnixNano(now))
		if err != nil {
			return err
		}
		id, err := res.LastInsertId()
		if err != nil {
			return err
		}
		p.ID = domain.PlaylistID(id)
		return writeTracks(ctx, tx, p.ID, p.TrackIDs)
	})
	return p, err
}

func writeTracks(ctx context.Context, tx *sql.Tx, id domain.PlaylistID, tracks []library.TrackID) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM playlist_tracks WHERE playlist_id = ?`, id); err != nil {
		return err
	}
	stmt, err := tx.PrepareContext(ctx, `INSERT INTO playlist_tracks (playlist_id, position, track_id) VALUES (?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()
	for i, tid := range tracks {
		if _, err := stmt.ExecContext(ctx, id, i, tid); err != nil {
			return err
		}
	}
	return nil
}

func (s *PlaylistRepository) touch(ctx context.Context, tx *sql.Tx, owner shared.UserID, id domain.PlaylistID, setName *string) error {
	q, args := `UPDATE playlists SET updated_at = ?`, []any{sqlitex.UnixNano(time.Now())}
	if setName != nil {
		q += `, name = ?`
		args = append(args, *setName)
	}
	res, err := tx.ExecContext(ctx, q+` WHERE id = ? AND owner_id = ?`, append(args, id, owner)...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return fmt.Errorf("playlist %d: %w", id, shared.ErrNotFound)
	}
	return nil
}

func (s *PlaylistRepository) Rename(ctx context.Context, owner shared.UserID, id domain.PlaylistID, name string) error {
	return s.db.InTx(ctx, func(tx *sql.Tx) error { return s.touch(ctx, tx, owner, id, &name) })
}

func (s *PlaylistRepository) SetTracks(ctx context.Context, owner shared.UserID, id domain.PlaylistID, tracks []library.TrackID) error {
	return s.db.InTx(ctx, func(tx *sql.Tx) error {
		if err := s.touch(ctx, tx, owner, id, nil); err != nil {
			return err
		}
		return writeTracks(ctx, tx, id, tracks)
	})
}

func (s *PlaylistRepository) Delete(ctx context.Context, owner shared.UserID, id domain.PlaylistID) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM playlists WHERE id = ? AND owner_id = ?`, id, owner)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return fmt.Errorf("playlist %d: %w", id, shared.ErrNotFound)
	}
	return nil
}

func (s *PlaylistRepository) AddFavorite(ctx context.Context, owner shared.UserID, track library.TrackID) error {
	_, err := s.db.ExecContext(ctx, `INSERT OR IGNORE INTO favorites (owner_id, track_id, created_at) VALUES (?, ?, ?)`,
		owner, track, sqlitex.UnixNano(time.Now()))
	return err
}

func (s *PlaylistRepository) RemoveFavorite(ctx context.Context, owner shared.UserID, track library.TrackID) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM favorites WHERE owner_id = ? AND track_id = ?`, owner, track)
	return err
}

// ListFavorites returns the favourite track ids, newest first. Resolving
// them to tracks is the use case's job (via library.Repository), so this
// store never depends on the library's table layout.
func (s *PlaylistRepository) ListFavorites(ctx context.Context, owner shared.UserID, p shared.Page) (shared.PageResult[library.TrackID], error) {
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM favorites WHERE owner_id = ?`, owner).Scan(&total); err != nil {
		return shared.PageResult[library.TrackID]{}, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT track_id FROM favorites
		WHERE owner_id = ? ORDER BY created_at DESC, track_id DESC LIMIT ? OFFSET ?`, owner, p.Limit, p.Offset)
	if err != nil {
		return shared.PageResult[library.TrackID]{}, err
	}
	defer rows.Close()
	var ids []library.TrackID
	for rows.Next() {
		var id library.TrackID
		if err := rows.Scan(&id); err != nil {
			return shared.PageResult[library.TrackID]{}, err
		}
		ids = append(ids, id)
	}
	return shared.PageResult[library.TrackID]{Items: ids, Total: total, Page: p}, rows.Err()
}

func (s *PlaylistRepository) FavoriteIDs(ctx context.Context, owner shared.UserID, among []library.TrackID) (map[library.TrackID]bool, error) {
	out := map[library.TrackID]bool{}
	for start := 0; start < len(among); start += 500 {
		chunk := among[start:min(start+500, len(among))]
		args := []any{owner}
		for _, id := range chunk {
			args = append(args, id)
		}
		rows, err := s.db.QueryContext(ctx, `SELECT track_id FROM favorites WHERE owner_id = ? AND track_id IN (`+sqlitex.Placeholders(len(chunk))+`)`, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var id library.TrackID
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return nil, err
			}
			out[id] = true
		}
		rows.Close()
	}
	return out, nil
}
