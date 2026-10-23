package domain

import (
	"regexp"
	"sort"
	"strings"
)

// DuplicateReason explains why tracks were grouped as duplicates.
type DuplicateReason string

const (
	// DuplicateIdentical: byte-identical files.
	DuplicateIdentical DuplicateReason = "identical"
	// DuplicateSameSong: same artist and title, e.g. MV vs lyric-video rips.
	DuplicateSameSong DuplicateReason = "same_song"
)

type DuplicateGroup struct {
	Reason DuplicateReason
	Tracks []Track
}

// FindDuplicates groups byte-identical files and likely re-uploads of the
// same song (same artist + normalised title). A track already reported as
// an identical copy is not reported again as a same-song duplicate.
func FindDuplicates(all []Track) []DuplicateGroup {
	byHash := map[string][]Track{}
	bySong := map[string][]Track{}
	for _, t := range all {
		if t.ContentHash != "" {
			byHash[t.ContentHash] = append(byHash[t.ContentHash], t)
		}
		key := strings.ToLower(t.GroupingArtist()) + "\x00" + normaliseTitle(t.Title)
		bySong[key] = append(bySong[key], t)
	}

	var groups []DuplicateGroup
	identical := map[TrackID]bool{}
	for _, ts := range byHash {
		if len(ts) > 1 {
			groups = append(groups, DuplicateGroup{Reason: DuplicateIdentical, Tracks: ts})
			for _, t := range ts[1:] {
				identical[t.ID] = true
			}
		}
	}
	for _, ts := range bySong {
		var distinct []Track
		for _, t := range ts {
			if !identical[t.ID] {
				distinct = append(distinct, t)
			}
		}
		if len(distinct) > 1 {
			groups = append(groups, DuplicateGroup{Reason: DuplicateSameSong, Tracks: distinct})
		}
	}
	sort.Slice(groups, func(i, j int) bool {
		if groups[i].Reason != groups[j].Reason {
			return groups[i].Reason < groups[j].Reason
		}
		return groups[i].Tracks[0].Title < groups[j].Tracks[0].Title
	})
	return groups
}

var (
	bracketed = regexp.MustCompile(`[(（\[【]([^)）\]】]*)[)）\]】]`)
	// variantWords mark a deliberately different recording, not a re-upload.
	variantWords = regexp.MustCompile(`(?i)(remix|mix|inst|instrumental|karaoke|off\s*vocal|arrange|live|acoustic|piano|orgel|short|tv\s*size|w/o|ver)`)
	titleNoise   = strings.NewReplacer(" ", "", "\t", "", "-", "", "_", "", ".", "", "～", "", "~", "")
)

// normaliseTitle folds case and drops decorative brackets ("(MV)", "[HD]")
// so re-uploads of one song compare equal, while keeping variant markers
// such as "(Remix)" or "(Instrumental)" that denote a different recording.
func normaliseTitle(s string) string {
	s = bracketed.ReplaceAllStringFunc(strings.ToLower(s), func(m string) string {
		if variantWords.MatchString(m) {
			return m
		}
		return ""
	})
	return titleNoise.Replace(s)
}
