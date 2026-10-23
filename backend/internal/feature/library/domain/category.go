package domain

import (
	"fmt"

	"musicstream/internal/shared"
	"musicstream/internal/shared/trackmeta"
)

// Types and Regions list the library's classification levels in display
// order (the folders <Type>/<Region>/ of the music library).
var (
	Types   = []string{trackmeta.TypeSong, trackmeta.TypeInstrumental}
	Regions = []string{trackmeta.RegionChinese, trackmeta.RegionJapanese, trackmeta.RegionKorean, trackmeta.RegionWestern, trackmeta.RegionOther}
)

// Category returns the type/region folder the track lives in; both are ""
// for files outside the two classification levels.
func (t Track) Category() trackmeta.Category {
	c, _, _ := trackmeta.SplitCategory(t.Path)
	return c
}

// CategoryFilter narrows listings to one type and/or region. Empty fields
// match everything.
type CategoryFilter struct {
	Type   string
	Region string
}

// Validate rejects values that are not library folders.
func (f CategoryFilter) Validate() error {
	if f.Type != "" && !contains(Types, f.Type) {
		return fmt.Errorf("type %q: %w", f.Type, shared.ErrInvalidArgument)
	}
	if f.Region != "" && !contains(Regions, f.Region) {
		return fmt.Errorf("region %q: %w", f.Region, shared.ErrInvalidArgument)
	}
	return nil
}

// IsZero reports whether the filter matches every track.
func (f CategoryFilter) IsZero() bool { return f.Type == "" && f.Region == "" }

// PathPrefixes returns the library path prefixes ("歌曲/華語/") the filter
// matches; nil when the filter is empty. Call Validate first.
func (f CategoryFilter) PathPrefixes() []string {
	if f.IsZero() {
		return nil
	}
	var out []string
	for _, t := range Types {
		for _, r := range Regions {
			if (f.Type == "" || f.Type == t) && (f.Region == "" || f.Region == r) {
				out = append(out, t+"/"+r+"/")
			}
		}
	}
	return out
}

// CategoryCount summarises one type/region folder.
type CategoryCount struct {
	Category    trackmeta.Category
	TrackCount  int
	ArtistCount int
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}
