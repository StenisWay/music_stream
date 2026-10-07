package trackmeta

import "strings"

// ArtistSet is an ArtistIndex built from the whole collection.
// Names from tags and folders are trusted immediately; names guessed from
// file names must be seen at least twice before they count as "known".
type ArtistSet struct {
	trusted    map[string]struct{}
	candidates map[string]int
}

func NewArtistSet() *ArtistSet {
	return &ArtistSet{trusted: map[string]struct{}{}, candidates: map[string]int{}}
}

func (s *ArtistSet) AddTrusted(name string) {
	if name = normaliseKey(name); name != "" {
		s.trusted[name] = struct{}{}
	}
}

func (s *ArtistSet) AddCandidate(name string) {
	if name = normaliseKey(name); name != "" {
		s.candidates[name]++
	}
}

func (s *ArtistSet) IsKnownArtist(name string) bool {
	key := normaliseKey(name)
	if key == "" {
		return false
	}
	if _, ok := s.trusted[key]; ok {
		return true
	}
	return s.candidates[key] >= 2
}

func normaliseKey(s string) string {
	return strings.ToLower(strings.TrimSpace(s))
}
