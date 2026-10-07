package infrastructure

import (
	"musicstream/internal/feature/library/application"
	"musicstream/internal/platform/audiotag"
)

// TagProber adapts the platform audio-tag reader to application.Prober.
type TagProber struct{ p *audiotag.Prober }

func NewTagProber(p *audiotag.Prober) TagProber { return TagProber{p: p} }

func (a TagProber) Probe(absPath string) (application.ProbeResult, error) {
	info, err := a.p.Probe(absPath)
	return application.ProbeResult{
		Tags: info.Tags, Duration: info.Duration, BitrateKbps: info.BitrateKbps, HasPicture: info.HasPicture,
	}, err
}
