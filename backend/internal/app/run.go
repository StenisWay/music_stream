package app

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"time"

	"musicstream/internal/platform/fswatch"
	"musicstream/internal/platform/tlsx"
)

// Run starts all background workers and the HTTP server, blocking until ctx
// is cancelled, then shuts down gracefully.
func (a *App) Run(ctx context.Context) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var wg sync.WaitGroup
	goFn := func(fn func()) {
		wg.Add(1)
		go func() { defer wg.Done(); fn() }()
	}

	goFn(func() {
		a.rescan(ctx)
		// Ingest after the first scan so duplicate detection sees the whole catalog.
		a.ingest(ctx)
	})
	goFn(func() { a.discover.Run(ctx) })
	if a.ingestor != nil {
		w := fswatch.New(a.ingestor.Inbox(), a.cfg.Library.IngestMinAge, a.ingest, a.log.With("component", "inbox-watcher"))
		goFn(func() {
			if err := w.Run(ctx); err != nil {
				a.log.Error("inbox watcher stopped", "err", err)
			}
		})
	}
	if a.cfg.Library.Watch {
		w := fswatch.New(a.media.Root(), 5*time.Second, a.rescan, a.log.With("component", "library-watcher"))
		goFn(func() {
			if err := w.Run(ctx); err != nil {
				a.log.Error("file watcher stopped", "err", err)
			}
		})
	}
	if iv := a.cfg.Library.RescanInterval; iv > 0 {
		goFn(func() { every(ctx, iv, func(ctx context.Context) { a.ingest(ctx); a.rescan(ctx) }) })
	}
	err := a.serveHTTP(ctx, cancel)
	wg.Wait()
	return err
}

// serveHTTP serves until ctx ends or the listener fails, then cancels the
// background workers and shuts the server down gracefully.
func (a *App) serveHTTP(ctx context.Context, cancel context.CancelFunc) error {
	srv := &http.Server{
		Addr:              a.cfg.Server.Listen,
		Handler:           a.handler,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		// No WriteTimeout: audio streams are long-lived; idle clients are
		// bounded by IdleTimeout and TCP keep-alives.
		ErrorLog: slog.NewLogLogger(a.log.Handler(), slog.LevelWarn),
	}
	if a.sec.cert != nil {
		srv.TLSConfig = tlsx.ServerConfig(*a.sec.cert)
	}

	errCh := make(chan error, 1)
	go func() {
		ln, err := net.Listen("tcp", srv.Addr)
		if err != nil {
			errCh <- err
			return
		}
		a.log.Info("listening", "addr", srv.Addr, "scheme", a.sec.scheme(), "library", a.media.Root())
		if a.sec.cert != nil {
			errCh <- srv.ServeTLS(ln, "", "")
		} else {
			errCh <- srv.Serve(ln)
		}
	}()

	var runErr error
	select {
	case <-ctx.Done():
	case err := <-errCh:
		if !errors.Is(err, http.ErrServerClosed) {
			runErr = err
		}
	}
	cancel()
	shutdownCtx, stop := context.WithTimeout(context.Background(), 10*time.Second)
	defer stop()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		a.log.Warn("http shutdown", "err", err)
	}
	return runErr
}

func (a *App) rescan(ctx context.Context) {
	if err := a.scanner.Refresh(ctx); err != nil && ctx.Err() == nil {
		a.log.Error("library scan failed", "err", err)
	}
}

// ingest moves new inbox files into the library (the ingestor refreshes the
// catalog itself when something moved).
func (a *App) ingest(ctx context.Context) {
	if a.ingestor == nil {
		return
	}
	if _, err := a.ingestor.Run(ctx); err != nil && ctx.Err() == nil {
		a.log.Error("ingest failed", "err", err)
	}
}

func every(ctx context.Context, d time.Duration, fn func(context.Context)) {
	t := time.NewTicker(d)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			fn(ctx)
		}
	}
}
