// Package app is the composition root: it builds each feature from its
// infrastructure, application and presentation layers, connects features
// through their ports, and owns the process lifecycle (HTTP server,
// watchers, schedulers). It contains no business rules.
package app

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"musicstream/internal/config"
	authapp "musicstream/internal/feature/auth/application"
	authinfra "musicstream/internal/feature/auth/infrastructure"
	authhttp "musicstream/internal/feature/auth/presentation"
	discoverapp "musicstream/internal/feature/discover/application"
	discoverinfra "musicstream/internal/feature/discover/infrastructure"
	discoverhttp "musicstream/internal/feature/discover/presentation"
	ingestapp "musicstream/internal/feature/ingest/application"
	ingestdomain "musicstream/internal/feature/ingest/domain"
	ingestinfra "musicstream/internal/feature/ingest/infrastructure"
	ingesthttp "musicstream/internal/feature/ingest/presentation"
	libraryapp "musicstream/internal/feature/library/application"
	libraryinfra "musicstream/internal/feature/library/infrastructure"
	libraryhttp "musicstream/internal/feature/library/presentation"
	pairingapp "musicstream/internal/feature/pairing/application"
	pairingdomain "musicstream/internal/feature/pairing/domain"
	pairinginfra "musicstream/internal/feature/pairing/infrastructure"
	playlistapp "musicstream/internal/feature/playlist/application"
	playlistinfra "musicstream/internal/feature/playlist/infrastructure"
	playlisthttp "musicstream/internal/feature/playlist/presentation"
	streamapp "musicstream/internal/feature/stream/application"
	streaminfra "musicstream/internal/feature/stream/infrastructure"
	streamhttp "musicstream/internal/feature/stream/presentation"
	systemapp "musicstream/internal/feature/system/application"
	systemhttp "musicstream/internal/feature/system/presentation"
	"musicstream/internal/platform/audiotag"
	"musicstream/internal/platform/sqlitex"
)

// App holds the wired application.
type App struct {
	cfg      config.Config
	log      *slog.Logger
	db       *sqlitex.DB
	media    *libraryinfra.MediaStore
	scanner  *libraryapp.Scanner
	ingestor *ingestapp.Ingestor // nil when no inbox is configured
	discover *discoverapp.Service
	pairing  *pairingapp.Pairing
	handler  http.Handler
	sec      security
}

// New wires every feature. It performs no network I/O.
func New(ctx context.Context, cfg config.Config, version string, log *slog.Logger) (*App, error) {
	db, err := openDatabase(ctx, filepath.Join(cfg.DataDir, "musicstream.db"))
	if err != nil {
		return nil, err
	}
	a := &App{cfg: cfg, log: log, db: db}
	ok := false
	defer func() {
		if !ok {
			a.Close()
		}
	}()
	if a.sec, err = loadSecurity(ctx, cfg, db, log); err != nil {
		return nil, err
	}
	httpLog := log.With("component", "http")
	tags := audiotag.NewProber()

	// ---- auth ----
	authSvc, err := a.wireAuth(ctx)
	if err != nil {
		return nil, err
	}

	// ---- library ----
	if a.media, err = libraryinfra.OpenMediaStore(cfg.Library.Root); err != nil {
		return nil, err
	}
	tracks := libraryinfra.NewTrackRepository(db)
	catalog := libraryapp.NewCatalog(tracks)
	a.scanner = libraryapp.NewScanner(a.media, libraryinfra.NewTagProber(tags), libraryinfra.FileHasher{}, tracks,
		log.With("feature", "library"))

	// ---- playlist, stream (read tracks through their own ports) ----
	playlists := playlistapp.NewService(playlistinfra.NewPlaylistRepository(db), tracks)
	stream := streamapp.NewService(tracks, a.media, streaminfra.NewFFmpeg(cfg.Transcode.FFmpeg), tags)

	// ---- ingest ----
	if cfg.Library.Inbox != "" {
		if a.ingestor, err = newIngestor(cfg, tracks, a.scanner, log.With("feature", "ingest")); err != nil {
			return nil, err
		}
	}

	// ---- discover (downloads land in the inbox; ingest files them) ----
	var inbox discoverapp.Inbox
	if a.ingestor != nil {
		inbox = discoverinfra.NewInboxDir(a.ingestor.Inbox())
	}
	a.discover = discoverapp.NewService(
		discoverinfra.NewYtDlp(cfg.Discover.YtDlp, cfg.Transcode.FFmpeg, filepath.Join(cfg.DataDir, "discover")),
		inbox, libraryArtists{tracks}, discoverapp.Config{}, log.With("feature", "discover"))

	// ---- pairing ----
	a.pairing = pairingapp.NewPairing(pairingapp.PairingConfig{
		Port: cfg.Server.Port(), Scheme: a.sec.scheme(), CertPin: a.sec.certPin, PublicKey: a.sec.publicKey,
	}, pairinginfra.LAN{})

	// ---- system ----
	status := systemapp.NewStatusService(version, catalog,
		func() (bool, time.Time) { st := a.scanner.Status(); return st.Running, st.LastScanAt },
		stream.CanTranscode)

	// ---- HTTP ----
	presenter := libraryhttp.Presenter{CanTranscode: stream.CanTranscode}
	a.handler = newRouter(authSvc, httpLog,
		&authhttp.Handler{Auth: authSvc, Log: httpLog},
		&systemhttp.Handler{Status: status, Log: httpLog},
		&libraryhttp.Handler{Catalog: catalog, Scanner: a.scanner, Presenter: presenter, Log: httpLog},
		&playlisthttp.Handler{Playlists: playlists, Presenter: presenter, Log: httpLog},
		&streamhttp.Handler{Stream: stream, Log: httpLog},
		&ingesthttp.Handler{Ingestor: a.ingestor, Log: httpLog},
		&discoverhttp.Handler{Discover: a.discover, Log: httpLog},
	)
	ok = true
	return a, nil
}

// openDatabase opens SQLite and applies each feature's migrations in
// dependency order (playlists reference tracks and users).
func openDatabase(ctx context.Context, file string) (*sqlitex.DB, error) {
	db, err := sqlitex.Open(ctx, file)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	for _, migrate := range []func(context.Context, *sqlitex.DB) error{
		libraryinfra.Migrate, authinfra.Migrate, playlistinfra.Migrate,
	} {
		if err := migrate(ctx, db); err != nil {
			db.Close()
			return nil, fmt.Errorf("migrate: %w", err)
		}
	}
	return db, nil
}

func (a *App) wireAuth(ctx context.Context) (*authapp.Service, error) {
	issuer, err := authinfra.NewJWT(a.sec.jwtSecret)
	if err != nil {
		return nil, err
	}
	svc := authapp.NewService(authinfra.NewUserRepository(a.db), authinfra.Bcrypt{}, issuer,
		authapp.Config{AccessTTL: a.cfg.Auth.AccessTTL, RefreshTTL: a.cfg.Auth.RefreshTTL})
	if err := svc.EnsureUser(ctx, a.cfg.Auth.Username, a.cfg.Auth.PasswordHash); err != nil {
		return nil, fmt.Errorf("provision user: %w", err)
	}
	return svc, nil
}

// newIngestor wires the inbox -> library pipeline. _misc, _duplicates and
// the undo logs live next to the library folder.
func newIngestor(cfg config.Config, catalog ingestapp.Catalog, refresh ingestapp.CatalogRefresher, log *slog.Logger) (*ingestapp.Ingestor, error) {
	if err := os.MkdirAll(cfg.Library.Inbox, 0o755); err != nil {
		return nil, fmt.Errorf("create inbox: %w", err)
	}
	lib, err := filepath.Abs(cfg.Library.Root)
	if err != nil {
		return nil, err
	}
	inbox, err := filepath.Abs(cfg.Library.Inbox)
	if err != nil {
		return nil, err
	}
	base := filepath.Dir(lib)
	layout := ingestdomain.Layout{
		SourceRoot: inbox, LibraryRoot: lib,
		MiscRoot: filepath.Join(base, "_misc"), DuplicatesRoot: filepath.Join(base, "_duplicates"),
	}
	newLog := func(started time.Time) (ingestapp.MoveLog, string, error) {
		p := filepath.Join(base, ".musicorg", "ingest-"+started.Format("20060102-150405")+".jsonl")
		l, err := ingestinfra.CreateMoveLog(p)
		return l, p, err
	}
	return ingestapp.NewIngestor(ingestinfra.NewLocalFS(), audiotag.NewReader(), catalog, refresh, layout,
		cfg.Library.IngestMinAge, newLog, log), nil
}

// libraryArtists adapts the track repository to discover's ArtistCatalog
// port, so discover does not depend on the library feature.
type libraryArtists struct{ tracks *libraryinfra.TrackRepository }

func (l libraryArtists) KnownArtists(ctx context.Context) ([]string, error) {
	all, err := l.tracks.All(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, 2*len(all))
	for _, t := range all {
		out = append(out, t.Artist, t.AlbumArtist)
	}
	return out, nil
}

// Pairing builds the connection code shown to the user.
func (a *App) Pairing() pairingdomain.PairingInfo {
	return a.pairing.Info()
}

// Close releases resources.
func (a *App) Close() {
	if a.media != nil {
		_ = a.media.Close()
	}
	if a.db != nil {
		_ = a.db.Close()
	}
}

// NewLogger builds the process logger from config.
func NewLogger(c config.Log) *slog.Logger {
	var level slog.Level
	_ = level.UnmarshalText([]byte(c.Level))
	opts := &slog.HandlerOptions{Level: level}
	if c.Format == "json" {
		return slog.New(slog.NewJSONHandler(os.Stderr, opts))
	}
	return slog.New(slog.NewTextHandler(os.Stderr, opts))
}
