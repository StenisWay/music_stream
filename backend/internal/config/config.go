// Package config loads server configuration from a YAML file with
// environment-variable overrides for secrets.
package config

import (
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"gopkg.in/yaml.v3"
)

type TLSMode string

const (
	TLSSelfSigned TLSMode = "self-signed"
	TLSFiles      TLSMode = "files"
	TLSOff        TLSMode = "off"
)

type Config struct {
	Server    Server    `yaml:"server"`
	Library   Library   `yaml:"library"`
	DataDir   string    `yaml:"dataDir"`
	Auth      Auth      `yaml:"auth"`
	Transcode Transcode `yaml:"transcode"`
	Discover  Discover  `yaml:"discover"`
	Log       Log       `yaml:"log"`
}

type Server struct {
	Listen   string  `yaml:"listen"`
	TLS      TLSMode `yaml:"tls"`
	CertFile string  `yaml:"certFile"`
	KeyFile  string  `yaml:"keyFile"`
}

// Port is the TCP port of Listen, put in the connection code. It is 0 when
// Listen has no valid port (rejected by Validate).
func (s Server) Port() int {
	_, p, err := net.SplitHostPort(s.Listen)
	if err != nil {
		return 0
	}
	n, err := strconv.Atoi(p)
	if err != nil || n < 1 || n > 65535 {
		return 0
	}
	return n
}

type Library struct {
	Root           string        `yaml:"root"`
	Watch          bool          `yaml:"watch"`
	RescanInterval time.Duration `yaml:"rescanInterval"`
	// Inbox is the source folder new music is dropped into; files are
	// classified and moved into Root automatically. Empty disables ingest.
	Inbox string `yaml:"inbox"`
	// IngestMinAge leaves files alone until they have not changed for this
	// long (copies/downloads in progress).
	IngestMinAge time.Duration `yaml:"ingestMinAge"`
}

type Auth struct {
	Username     string        `yaml:"username"`
	PasswordHash string        `yaml:"passwordHash"`
	AccessTTL    time.Duration `yaml:"accessTTL"`
	RefreshTTL   time.Duration `yaml:"refreshTTL"`
}

type Transcode struct {
	FFmpeg string `yaml:"ffmpeg"`
}

// Discover configures searching and downloading songs from online sources.
// It needs yt-dlp (plus ffmpeg from Transcode) and an inbox.
type Discover struct {
	YtDlp string `yaml:"ytdlp"`
}

type Log struct {
	Level  string `yaml:"level"`  // debug | info | warn | error
	Format string `yaml:"format"` // text | json
}

// Default returns the configuration used for omitted fields.
func Default() Config {
	return Config{
		Server: Server{Listen: ":8443", TLS: TLSSelfSigned},
		Library: Library{
			Root: "../music_data/library", Watch: true, RescanInterval: 6 * time.Hour,
			Inbox: "../music_data/inbox", IngestMinAge: 10 * time.Second,
		},
		DataDir:   "./data",
		Auth:      Auth{Username: "admin", AccessTTL: time.Hour, RefreshTTL: 90 * 24 * time.Hour},
		Transcode: Transcode{FFmpeg: "ffmpeg"},
		Discover:  Discover{YtDlp: "yt-dlp"},
		Log:       Log{Level: "info", Format: "text"},
	}
}

// Load reads path (if it exists) over the defaults, applies env overrides and
// resolves relative paths against the config file's directory.
func Load(path string) (Config, error) {
	cfg := Default()
	base := "."
	if path != "" {
		raw, err := os.ReadFile(path)
		switch {
		case errors.Is(err, os.ErrNotExist):
			return Config{}, fmt.Errorf("config file %s not found (copy config.example.yaml)", path)
		case err != nil:
			return Config{}, err
		}
		if err := yaml.Unmarshal(raw, &cfg); err != nil {
			return Config{}, fmt.Errorf("parse %s: %w", path, err)
		}
		base = filepath.Dir(path)
	}
	if v := os.Getenv("MUSICSTREAM_PASSWORD_HASH"); v != "" {
		cfg.Auth.PasswordHash = v
	}
	if v := os.Getenv("MUSICSTREAM_LIBRARY_ROOT"); v != "" {
		cfg.Library.Root = v
	}
	if v := os.Getenv("MUSICSTREAM_LISTEN"); v != "" {
		cfg.Server.Listen = v
	}
	resolve := func(p *string) {
		if *p != "" && !filepath.IsAbs(*p) {
			*p = filepath.Join(base, *p)
		}
	}
	resolve(&cfg.Library.Root)
	resolve(&cfg.Library.Inbox)
	resolve(&cfg.DataDir)
	resolve(&cfg.Server.CertFile)
	resolve(&cfg.Server.KeyFile)
	return cfg, cfg.Validate()
}

func (c Config) Validate() error {
	var errs []error
	if c.Library.Root == "" {
		errs = append(errs, errors.New("library.root is required"))
	}
	if c.Auth.Username == "" {
		errs = append(errs, errors.New("auth.username is required"))
	}
	if c.Auth.PasswordHash == "" {
		errs = append(errs, errors.New("auth.passwordHash is required (run: musicserver hash-password)"))
	}
	switch c.Server.TLS {
	case TLSSelfSigned, TLSOff:
	case TLSFiles:
		if c.Server.CertFile == "" || c.Server.KeyFile == "" {
			errs = append(errs, errors.New("server.certFile and server.keyFile are required for tls: files"))
		}
	default:
		errs = append(errs, fmt.Errorf("server.tls must be self-signed, files or off (got %q)", c.Server.TLS))
	}
	if c.Server.Port() == 0 {
		errs = append(errs, fmt.Errorf("server.listen must include a port, e.g. \":8443\" (got %q)", c.Server.Listen))
	}
	if c.Auth.AccessTTL <= 0 || c.Auth.RefreshTTL <= 0 {
		errs = append(errs, errors.New("auth TTLs must be positive"))
	}
	return errors.Join(errs...)
}
