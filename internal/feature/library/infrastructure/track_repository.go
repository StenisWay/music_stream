// Package infrastructure implements the library ports: the SQLite track
// repository, root-confined access to the music folder, tag probing and
// content hashing.
package infrastructure

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"
	"strings"
	"time"

	"musicstream/internal/feature/library/domain"
	"musicstream/internal/platform/sqlitex"
	"musicstream/internal/shared"
	"musicstream/internal/shared/trackmeta"
)

//go:embed migrations/*.sql
var migrations embed.FS

// TrackRepository implements domain.TrackRepository on SQLite.
type TrackRepository struct{ db *sqlitex.DB }

func NewTrackRepository(db *sqlitex.DB) *TrackRepository { return &TrackRepository{db: db} }

// Migrate creates/updates the tracks schema.
func Migrate(ctx context.Context, db *sqlitex.DB) error {
	sub, _ := fs.Sub(migrations, "migrations")
	return db.Migrate(ctx, "library", sub)
}

// trackColumns lists the columns scanTrack expects, in order.
const trackColumns = `id, path, title, artist, album, album_artist, track_no, year, genre,
	duration_ms, bitrate_kbps, size, mod_time, format, content_hash, has_artwork,
	meta_source, user_edited, added_at, updated_at`

// scanTrack reads one row selected with trackColumns.
func scanTrack(r sqlitex.RowScanner) (domain.Track, error) {
	var t domain.Track
	var durationMs, modTime, addedAt, updatedAt int64
	var hasArt, edited int
	var format, source string
	err := r.Scan(&t.ID, &t.Path, &t.Title, &t.Artist, &t.Album, &t.AlbumArtist, &t.TrackNo, &t.Year, &t.Genre,
		&durationMs, &t.BitrateKbps, &t.Size, &modTime, &format, &t.ContentHash, &hasArt,
		&source, &edited, &addedAt, &updatedAt)
	if err != nil {
		return domain.Track{}, err
	}
	t.Duration = time.Duration(durationMs) * time.Millisecond
	t.ModTime, t.AddedAt, t.UpdatedAt = sqlitex.FromUnixNano(modTime), sqlitex.FromUnixNano(addedAt), sqlitex.FromUnixNano(updatedAt)
	t.Format, t.MetaSource = domain.AudioFormat(format), trackmeta.Source(source)
	t.HasArtwork, t.UserEdited = hasArt == 1, edited == 1
	return t, nil
}

// collectTracks drains rows selected with trackColumns.
func collectTracks(rows *sql.Rows) ([]domain.Track, error) {
	defer rows.Close()
	var out []domain.Track
	for rows.Next() {
		t, err := scanTrack(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (s *TrackRepository) Get(ctx context.Context, id domain.TrackID) (domain.Track, error) {
	t, err := scanTrack(s.db.QueryRowContext(ctx, `SELECT `+trackColumns+` FROM tracks WHERE id = ?`, id))
	return t, sqlitex.NotFound(err, fmt.Sprintf("track %d", id))
}

func (s *TrackRepository) All(ctx context.Context) ([]domain.Track, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+trackColumns+` FROM tracks`)
	if err != nil {
		return nil, err
	}
	return collectTracks(rows)
}

func (s *TrackRepository) ListByIDs(ctx context.Context, ids []domain.TrackID) ([]domain.Track, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	var out []domain.Track
	// Chunk to stay below SQLite's bound-parameter limit.
	for start := 0; start < len(ids); start += 500 {
		chunk := ids[start:min(start+500, len(ids))]
		args := make([]any, len(chunk))
		for i, id := range chunk {
			args[i] = id
		}
		rows, err := s.db.QueryContext(ctx, `SELECT `+trackColumns+` FROM tracks WHERE id IN (`+sqlitex.Placeholders(len(chunk))+`)`, args...)
		if err != nil {
			return nil, err
		}
		ts, err := collectTracks(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, ts...)
	}
	return out, nil
}

func trackOrder(s domain.TrackSort) string {
	switch s {
	case domain.SortByArtist:
		return "grouping_artist COLLATE NOCASE, album COLLATE NOCASE, track_no, title COLLATE NOCASE"
	case domain.SortByAlbum:
		return "album COLLATE NOCASE, track_no, title COLLATE NOCASE"
	case domain.SortByAddedDesc:
		return "added_at DESC, id DESC"
	default:
		return "title COLLATE NOCASE, id"
	}
}

func trackWhere(f domain.TrackFilter) (string, []any) {
	var conds []string
	var args []any
	if f.Query != "" {
		conds = append(conds, `search_text LIKE ? ESCAPE '\'`)
		args = append(args, sqlitex.LikePattern(f.Query))
	}
	if f.ArtistID != "" {
		conds = append(conds, "artist_id = ?")
		args = append(args, string(f.ArtistID))
	}
	if f.AlbumID != "" {
		conds = append(conds, "album_id = ?")
		args = append(args, string(f.AlbumID))
	}
	if len(conds) == 0 {
		return "", nil
	}
	return " WHERE " + strings.Join(conds, " AND "), args
}

func (s *TrackRepository) List(ctx context.Context, f domain.TrackFilter, p shared.Page) (shared.PageResult[domain.Track], error) {
	where, args := trackWhere(f)
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM tracks`+where, args...).Scan(&total); err != nil {
		return shared.PageResult[domain.Track]{}, err
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+trackColumns+` FROM tracks`+where+` ORDER BY `+trackOrder(f.Sort)+` LIMIT ? OFFSET ?`,
		append(args, p.Limit, p.Offset)...)
	if err != nil {
		return shared.PageResult[domain.Track]{}, err
	}
	items, err := collectTracks(rows)
	if err != nil {
		return shared.PageResult[domain.Track]{}, err
	}
	return shared.PageResult[domain.Track]{Items: items, Total: total, Page: p}, nil
}

func (s *TrackRepository) Count(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM tracks`).Scan(&n)
	return n, err
}

func (s *TrackRepository) Upsert(ctx context.Context, t domain.Track) (domain.Track, error) {
	t.UpdatedAt = time.Now()
	if t.AddedAt.IsZero() {
		t.AddedAt = t.UpdatedAt
	}
	search := strings.ToLower(strings.Join([]string{t.Title, t.Artist, t.AlbumArtist, t.Album}, " "))
	args := []any{
		t.Path, t.Title, t.Artist, t.Album, t.AlbumArtist, t.GroupingArtist(), string(t.ArtistID()), string(t.AlbumID()),
		t.TrackNo, t.Year, t.Genre, t.Duration.Milliseconds(), t.BitrateKbps, t.Size, sqlitex.UnixNano(t.ModTime),
		string(t.Format), t.ContentHash, sqlitex.BoolInt(t.HasArtwork), string(t.MetaSource), sqlitex.BoolInt(t.UserEdited), search,
		sqlitex.UnixNano(t.AddedAt), sqlitex.UnixNano(t.UpdatedAt),
	}
	if t.ID == 0 {
		res, err := s.db.ExecContext(ctx, `INSERT INTO tracks (path, title, artist, album, album_artist, grouping_artist,
			artist_id, album_id, track_no, year, genre, duration_ms, bitrate_kbps, size, mod_time, format, content_hash,
			has_artwork, meta_source, user_edited, search_text, added_at, updated_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, args...)
		if err != nil {
			return domain.Track{}, err
		}
		id, err := res.LastInsertId()
		if err != nil {
			return domain.Track{}, err
		}
		t.ID = domain.TrackID(id)
		return t, nil
	}
	res, err := s.db.ExecContext(ctx, `UPDATE tracks SET path=?, title=?, artist=?, album=?, album_artist=?,
		grouping_artist=?, artist_id=?, album_id=?, track_no=?, year=?, genre=?, duration_ms=?, bitrate_kbps=?, size=?,
		mod_time=?, format=?, content_hash=?, has_artwork=?, meta_source=?, user_edited=?, search_text=?, added_at=?,
		updated_at=? WHERE id=?`, append(args, t.ID)...)
	if err != nil {
		return domain.Track{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return domain.Track{}, fmt.Errorf("track %d: %w", t.ID, shared.ErrNotFound)
	}
	return t, nil
}

func (s *TrackRepository) Delete(ctx context.Context, ids []domain.TrackID) error {
	if len(ids) == 0 {
		return nil
	}
	return s.db.InTx(ctx, func(tx *sql.Tx) error {
		for _, id := range ids {
			if _, err := tx.ExecContext(ctx, `DELETE FROM tracks WHERE id = ?`, id); err != nil {
				return err
			}
		}
		return nil
	})
}

// ---- aggregates ----

const artistSelect = `SELECT artist_id, MIN(grouping_artist), COUNT(*), COUNT(DISTINCT NULLIF(album_id, '')),
	COALESCE(MAX(CASE WHEN has_artwork = 1 THEN id END), 0) FROM tracks`

func scanArtist(r sqlitex.RowScanner) (domain.Artist, error) {
	var a domain.Artist
	var id string
	err := r.Scan(&id, &a.Name, &a.TrackCount, &a.AlbumCount, &a.ArtworkTrackID)
	a.ID = domain.ArtistID(id)
	return a, err
}

func (s *TrackRepository) ListArtists(ctx context.Context, query string, p shared.Page) (shared.PageResult[domain.Artist], error) {
	where, args := "", []any{}
	if query != "" {
		where = ` WHERE lower(grouping_artist) LIKE ? ESCAPE '\'`
		args = append(args, sqlitex.LikePattern(query))
	}
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(DISTINCT artist_id) FROM tracks`+where, args...).Scan(&total); err != nil {
		return shared.PageResult[domain.Artist]{}, err
	}
	rows, err := s.db.QueryContext(ctx, artistSelect+where+` GROUP BY artist_id ORDER BY MIN(grouping_artist) COLLATE NOCASE LIMIT ? OFFSET ?`,
		append(args, p.Limit, p.Offset)...)
	if err != nil {
		return shared.PageResult[domain.Artist]{}, err
	}
	defer rows.Close()
	var items []domain.Artist
	for rows.Next() {
		a, err := scanArtist(rows)
		if err != nil {
			return shared.PageResult[domain.Artist]{}, err
		}
		items = append(items, a)
	}
	return shared.PageResult[domain.Artist]{Items: items, Total: total, Page: p}, rows.Err()
}

func (s *TrackRepository) GetArtist(ctx context.Context, id domain.ArtistID) (domain.Artist, error) {
	a, err := scanArtist(s.db.QueryRowContext(ctx, artistSelect+` WHERE artist_id = ? GROUP BY artist_id`, string(id)))
	return a, sqlitex.NotFound(err, "artist "+string(id))
}

const albumSelect = `SELECT album_id, MIN(album), MIN(grouping_artist), artist_id, MAX(year), COUNT(*), SUM(duration_ms),
	COALESCE(MAX(CASE WHEN has_artwork = 1 THEN id END), 0) FROM tracks`

func scanAlbum(r sqlitex.RowScanner) (domain.Album, error) {
	var a domain.Album
	var id, artistID string
	var durationMs int64
	err := r.Scan(&id, &a.Title, &a.Artist, &artistID, &a.Year, &a.TrackCount, &durationMs, &a.ArtworkTrackID)
	a.ID, a.ArtistID, a.Duration = domain.AlbumID(id), domain.ArtistID(artistID), time.Duration(durationMs)*time.Millisecond
	return a, err
}

func (s *TrackRepository) ListAlbums(ctx context.Context, artist domain.ArtistID, query string, p shared.Page) (shared.PageResult[domain.Album], error) {
	conds, args := []string{"album_id != ''"}, []any{}
	if artist != "" {
		conds = append(conds, "artist_id = ?")
		args = append(args, string(artist))
	}
	if query != "" {
		conds = append(conds, `lower(album) LIKE ? ESCAPE '\'`)
		args = append(args, sqlitex.LikePattern(query))
	}
	where := " WHERE " + strings.Join(conds, " AND ")
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(DISTINCT album_id) FROM tracks`+where, args...).Scan(&total); err != nil {
		return shared.PageResult[domain.Album]{}, err
	}
	rows, err := s.db.QueryContext(ctx, albumSelect+where+` GROUP BY album_id ORDER BY MIN(album) COLLATE NOCASE LIMIT ? OFFSET ?`,
		append(args, p.Limit, p.Offset)...)
	if err != nil {
		return shared.PageResult[domain.Album]{}, err
	}
	defer rows.Close()
	var items []domain.Album
	for rows.Next() {
		a, err := scanAlbum(rows)
		if err != nil {
			return shared.PageResult[domain.Album]{}, err
		}
		items = append(items, a)
	}
	return shared.PageResult[domain.Album]{Items: items, Total: total, Page: p}, rows.Err()
}

func (s *TrackRepository) GetAlbum(ctx context.Context, id domain.AlbumID) (domain.Album, error) {
	a, err := scanAlbum(s.db.QueryRowContext(ctx, albumSelect+` WHERE album_id = ? GROUP BY album_id`, string(id)))
	return a, sqlitex.NotFound(err, "album "+string(id))
}
