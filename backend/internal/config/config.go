// Package config loads server configuration from a YAML file with
// environment-variable overrides for secrets.
package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
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
	Endpoint  Endpoint  `yaml:"endpoint"`
	Transcode Transcode `yaml:"transcode"`
	Log       Log       `yaml:"log"`
}

type Server struct {
	Listen     string  `yaml:"listen"`
	PublicPort int     `yaml:"publicPort"` // port forwarded on the router
	TLS        TLSMode `yaml:"tls"`
	CertFile   string  `yaml:"certFile"`
	KeyFile    string  `yaml:"keyFile"`
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

type Endpoint struct {
	Enabled       bool          `yaml:"enabled"`
	ServerID      string        `yaml:"serverId"`
	CheckInterval time.Duration `yaml:"checkInterval"`
	Firebase      Firebase      `yaml:"firebase"`
}

type Firebase struct {
	DatabaseURL     string `yaml:"databaseUrl"`
	CredentialsFile string `yaml:"credentialsFile"`
}

type Transcode struct {
	FFmpeg string `yaml:"ffmpeg"`
}

type Log struct {
	Level  string `yaml:"level"`  // debug | info | warn | error
	Format string `yaml:"format"` // text | json
}

// Default returns the configuration used for omitted fields.
func Default() Config {
	return Config{
		Server: Server{Listen: ":8443", PublicPort: 8443, TLS: TLSSelfSigned},
		Library: Library{
			Root: "../music_data/library", Watch: true, RescanInterval: 6 * time.Hour,
			Inbox: "../music_data/inbox", IngestMinAge: 10 * time.Second,
		},
		DataDir:   "./data",
		Auth:      Auth{Username: "admin", AccessTTL: time.Hour, RefreshTTL: 90 * 24 * time.Hour},
		Endpoint:  Endpoint{CheckInterval: 5 * time.Minute},
		Transcode: Transcode{FFmpeg: "ffmpeg"},
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
	resolve(&cfg.Endpoint.Firebase.CredentialsFile)
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
	if c.Endpoint.Enabled && c.Endpoint.Firebase.DatabaseURL != "" && c.Endpoint.Firebase.CredentialsFile == "" {
		errs = append(errs, errors.New("endpoint.firebase.credentialsFile is required when databaseUrl is set"))
	}
	if c.Endpoint.Enabled && c.Server.TLS == TLSOff {
		errs = append(errs, errors.New("server.tls: off cannot be combined with endpoint.enabled: the server would be reachable from the internet without encryption"))
	}
	if c.Auth.AccessTTL <= 0 || c.Auth.RefreshTTL <= 0 {
		errs = append(errs, errors.New("auth TTLs must be positive"))
	}
	return errors.Join(errs...)
}
