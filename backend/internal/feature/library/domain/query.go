package domain

// TrackSort is the ordering of track listings.
type TrackSort string

const (
	SortByTitle     TrackSort = "title"
	SortByArtist    TrackSort = "artist"
	SortByAlbum     TrackSort = "album"
	SortByAddedDesc TrackSort = "-addedAt"
)

func (s TrackSort) Valid() bool {
	switch s {
	case SortByTitle, SortByArtist, SortByAlbum, SortByAddedDesc:
		return true
	}
	return false
}

// TrackFilter narrows a track listing. Empty fields are ignored.
type TrackFilter struct {
	Query    string // case-insensitive substring over title/artist/album
	ArtistID ArtistID
	AlbumID  AlbumID
	Category CategoryFilter
	Sort     TrackSort
}
