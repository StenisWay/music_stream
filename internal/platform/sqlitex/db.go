// Package sqlitex owns the SQLite connection (pure Go driver, no cgo) and a
// per-feature migration runner. Each feature's store package embeds its own
// migrations, so a feature's schema lives next to the code that uses it.
package sqlitex

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite" // registers the "sqlite" driver

	"musicstream/internal/shared"
)

// DB is the shared connection pool.
type DB struct {
	*sql.DB
}

// Open opens (creating if needed) the database file and prepares the
// bookkeeping tables. Feature stores call Migrate afterwards.
func Open(ctx context.Context, file string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
		return nil, err
	}
	dsn := "file:" + file + "?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=synchronous(NORMAL)"
	conn, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// SQLite allows one writer; a small pool avoids SQLITE_BUSY storms.
	conn.SetMaxOpenConns(4)
	if err := conn.PingContext(ctx); err != nil {
		conn.Close()
		return nil, err
	}
	db := &DB{conn}
	_, err = db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			feature    TEXT    NOT NULL,
			version    INTEGER NOT NULL,
			applied_at INTEGER NOT NULL,
			PRIMARY KEY (feature, version)
		);
		CREATE TABLE IF NOT EXISTS settings (
			key   TEXT PRIMARY KEY,
			value TEXT NOT NULL
		);`)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("prepare schema: %w", err)
	}
	return db, nil
}

// Migrate applies the "NNNN_description.sql" files of fsys that have not
// been applied for feature yet, each in its own transaction.
func (db *DB) Migrate(ctx context.Context, feature string, fsys fs.FS) error {
	names, err := fs.Glob(fsys, "*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, name := range names {
		version, err := strconv.Atoi(strings.SplitN(path.Base(name), "_", 2)[0])
		if err != nil {
			return fmt.Errorf("%s: bad migration name %s", feature, name)
		}
		var applied int
		if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM schema_migrations WHERE feature = ? AND version = ?`,
			feature, version).Scan(&applied); err != nil {
			return err
		}
		if applied > 0 {
			continue
		}
		body, err := fs.ReadFile(fsys, name)
		if err != nil {
			return err
		}
		err = db.InTx(ctx, func(tx *sql.Tx) error {
			if _, err := tx.ExecContext(ctx, string(body)); err != nil {
				return fmt.Errorf("%s/%s: %w", feature, name, err)
			}
			_, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations (feature, version, applied_at) VALUES (?, ?, ?)`,
				feature, version, time.Now().UnixNano())
			return err
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// InTx runs fn in a transaction, rolling back on error.
func (db *DB) InTx(ctx context.Context, fn func(*sql.Tx) error) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		_ = tx.Rollback()
		return err
	}
	return tx.Commit()
}

// GetSetting / PutSetting implement a tiny key/value store for server state
// (generated secrets, server id).
func (db *DB) GetSetting(ctx context.Context, key string) (string, bool, error) {
	var v string
	err := db.QueryRowContext(ctx, `SELECT value FROM settings WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	return v, err == nil, err
}

func (db *DB) PutSetting(ctx context.Context, key, value string) error {
	_, err := db.ExecContext(ctx, `INSERT INTO settings (key, value) VALUES (?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}

// ---- helpers shared by feature stores ----

// RowScanner is satisfied by *sql.Row and *sql.Rows.
type RowScanner interface{ Scan(dest ...any) error }

// NotFound maps sql.ErrNoRows to shared.ErrNotFound.
func NotFound(err error, what string) error {
	if errors.Is(err, sql.ErrNoRows) {
		return fmt.Errorf("%s: %w", what, shared.ErrNotFound)
	}
	return err
}

func UnixNano(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixNano()
}

func FromUnixNano(n int64) time.Time {
	if n == 0 {
		return time.Time{}
	}
	return time.Unix(0, n)
}

func BoolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// LikePattern escapes s for a LIKE ... ESCAPE '\' substring match.
func LikePattern(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return "%" + r.Replace(strings.ToLower(s)) + "%"
}

func Placeholders(n int) string {
	return strings.TrimSuffix(strings.Repeat("?,", n), ",")
}
