package domain

import (
	"musicstream/internal/shared"
	"strings"
)

// MetadataPatch is a user-initiated metadata correction. Nil fields are left
// unchanged.
type MetadataPatch struct {
	Title       *string
	Artist      *string
	Album       *string
	AlbumArtist *string
	TrackNo     *int
	Year        *int
	Genre       *string
}

// Apply validates and applies the patch, marking the track as user edited.
func (p MetadataPatch) Apply(t *Track) error {
	if p.Title != nil {
		v := strings.TrimSpace(*p.Title)
		if v == "" {
			return shared.ErrInvalidArgument
		}
		t.Title = v
	}
	set := func(dst *string, src *string) {
		if src != nil {
			*dst = strings.TrimSpace(*src)
		}
	}
	set(&t.Artist, p.Artist)
	set(&t.Album, p.Album)
	set(&t.AlbumArtist, p.AlbumArtist)
	set(&t.Genre, p.Genre)
	if p.TrackNo != nil {
		if *p.TrackNo < 0 || *p.TrackNo > 999 {
			return shared.ErrInvalidArgument
		}
		t.TrackNo = *p.TrackNo
	}
	if p.Year != nil {
		if *p.Year < 0 || *p.Year > 9999 {
			return shared.ErrInvalidArgument
		}
		t.Year = *p.Year
	}
	t.UserEdited = true
	return nil
}
