// Command musicserver is the self-hosted music streaming server.
//
//	musicserver serve -config config.yaml   # run the server
//	musicserver hash-password               # create auth.passwordHash
//	musicserver pairing-code -config ...    # print the app connection code
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"golang.org/x/term"

	"musicstream/internal/app"
	"musicstream/internal/config"
	authinfra "musicstream/internal/feature/auth/infrastructure"
)

// version is overridden at build time: -ldflags "-X main.version=1.2.3".
var version = "dev"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) == 0 {
		args = []string{"serve"}
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "serve":
		return serve(rest)
	case "hash-password":
		return hashPassword()
	case "pairing-code":
		return pairingCode(rest)
	case "version":
		fmt.Println(version)
		return nil
	default:
		return fmt.Errorf("unknown command %q (serve | hash-password | pairing-code | version)", cmd)
	}
}

func loadConfig(args []string, name string) (config.Config, error) {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	path := fs.String("config", "config.yaml", "path to config file")
	if err := fs.Parse(args); err != nil {
		return config.Config{}, err
	}
	return config.Load(*path)
}

func serve(args []string) error {
	cfg, err := loadConfig(args, "serve")
	if err != nil {
		return err
	}
	log := app.NewLogger(cfg.Log)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	a, err := app.New(ctx, cfg, version, log)
	if err != nil {
		return err
	}
	defer a.Close()
	code := a.Pairing(ctx).Encode()
	log.Info("app connection code (paste into the Android app)", "code", code)
	return a.Run(ctx)
}

func pairingCode(args []string) error {
	cfg, err := loadConfig(args, "pairing-code")
	if err != nil {
		return err
	}
	ctx := context.Background()
	a, err := app.New(ctx, cfg, version, app.NewLogger(config.Log{Level: "warn"}))
	if err != nil {
		return err
	}
	defer a.Close()
	fmt.Println(a.Pairing(ctx).Encode())
	return nil
}

func hashPassword() error {
	pw, err := readPassword("New password: ")
	if err != nil {
		return err
	}
	if len(pw) < 8 {
		return errors.New("password must be at least 8 characters")
	}
	if term.IsTerminal(int(os.Stdin.Fd())) {
		again, err := readPassword("Repeat: ")
		if err != nil {
			return err
		}
		if again != pw {
			return errors.New("passwords do not match")
		}
	}
	h, err := authinfra.Bcrypt{}.Hash(pw)
	if err != nil {
		return err
	}
	fmt.Println(h)
	return nil
}

func readPassword(prompt string) (string, error) {
	if term.IsTerminal(int(os.Stdin.Fd())) {
		fmt.Fprint(os.Stderr, prompt)
		b, err := term.ReadPassword(int(os.Stdin.Fd()))
		fmt.Fprintln(os.Stderr)
		return string(b), err
	}
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}
