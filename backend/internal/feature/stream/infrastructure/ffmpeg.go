// Package infrastructure converts formats Android cannot play (WMA) using
// ffmpeg.
package infrastructure

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os/exec"
)

// FFmpeg implements application.Transcoder by piping ffmpeg's stdout.
type FFmpeg struct {
	bin string
}

// New returns a transcoder; Available() is false when ffmpeg is missing.
func NewFFmpeg(bin string) *FFmpeg {
	if bin == "" {
		bin = "ffmpeg"
	}
	if p, err := exec.LookPath(bin); err == nil {
		return &FFmpeg{bin: p}
	}
	return &FFmpeg{}
}

func (f *FFmpeg) Available() bool { return f.bin != "" }

func (f *FFmpeg) ToMP3(ctx context.Context, absPath string, w io.Writer) error {
	if !f.Available() {
		return fmt.Errorf("ffmpeg not found")
	}
	cmd := exec.CommandContext(ctx, f.bin,
		"-hide_banner", "-loglevel", "error", "-nostdin",
		"-i", absPath, "-vn", "-map_metadata", "-1",
		"-codec:a", "libmp3lame", "-q:a", "2", "-f", "mp3", "pipe:1")
	var stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = w, &stderr
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return ctx.Err() // client went away
		}
		return fmt.Errorf("ffmpeg: %w: %s", err, bytes.TrimSpace(stderr.Bytes()))
	}
	return nil
}
