// Package application reports the server status by aggregating read-only
// probes of other features. Each probe is a port wired by the composition
// root, so this feature depends on no other feature.
package application

import (
	"context"
	"time"
)

// TrackCounter counts the tracks in the catalog.
type TrackCounter interface {
	CountTracks(ctx context.Context) (int, error)
}

// ScanState reports whether the library scanner is busy and when it last finished.
type ScanState func() (running bool, lastScanAt time.Time)

// TranscodeCapability reports whether on-the-fly transcoding is available.
type TranscodeCapability func() bool

// Status is a snapshot of the server.
type Status struct {
	Version            string
	TrackCount         int
	Scanning           bool
	LastScanAt         time.Time
	TranscodeAvailable bool
}

type StatusService struct {
	version   string
	tracks    TrackCounter
	scan      ScanState
	transcode TranscodeCapability
}

func NewStatusService(version string, tracks TrackCounter, scan ScanState, transcode TranscodeCapability) *StatusService {
	return &StatusService{version: version, tracks: tracks, scan: scan, transcode: transcode}
}

func (s *StatusService) Version() string { return s.version }

func (s *StatusService) Status(ctx context.Context) (Status, error) {
	n, err := s.tracks.CountTracks(ctx)
	if err != nil {
		return Status{}, err
	}
	running, last := s.scan()
	return Status{
		Version: s.version, TrackCount: n, Scanning: running, LastScanAt: last,
		TranscodeAvailable: s.transcode(),
	}, nil
}
