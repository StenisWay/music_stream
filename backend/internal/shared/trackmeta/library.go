package trackmeta

import (
	"path"
	"regexp"
	"strconv"
	"strings"
)

var (
	collisionSuffix = regexp.MustCompile(`\s\(\d+\)$`)
	canonicalTrack  = regexp.MustCompile(`^(\d{2,3}) - (.+)$`)
)

// ResolveInLibrary resolves metadata for a file inside the organised
// library, whose layout is <Artist>/[<Album>/][NN - ]<Title>.ext (see
// LibraryPath). Folder and file names were chosen by the organizer or by the
// user and are therefore authoritative; tags only contribute details and
// their original spelling (before path sanitising).
//
// Files dropped directly into the library root fall back to Resolve.
func ResolveInLibrary(tags Meta, relPath string, idx ArtistIndex) (Meta, Source) {
	relPath = path.Clean(strings.ReplaceAll(relPath, "\\", "/"))
	dirs := strings.Split(path.Dir(relPath), "/")
	if path.Dir(relPath) == "." || len(dirs) > 2 {
		return Resolve(tags, relPath, idx)
	}
	t := sanitizeTags(tags)

	folderArtist := dirs[0]
	folderAlbum := ""
	if len(dirs) == 2 {
		folderAlbum = dirs[1]
	}

	base := strings.TrimSuffix(path.Base(relPath), path.Ext(relPath))
	base = collisionSuffix.ReplaceAllString(base, "")
	trackNo := 0
	if mm := canonicalTrack.FindStringSubmatch(base); mm != nil && folderAlbum != "" {
		trackNo, _ = strconv.Atoi(mm[1])
		base = mm[2]
	}

	out := Meta{Year: t.Year, Genre: t.Genre, TrackNo: t.TrackNo}
	if trackNo > 0 {
		out.TrackNo = trackNo
	}

	// Title: the file name, unless the tag is the same text before sanitising.
	switch {
	case t.Title != "" && SanitizeSegment(t.Title) == base:
		out.Title = t.Title
	case strings.Contains(base, " - ") && t.Title == "":
		// A file the user dropped into an artist folder: "Artist - Title".
		out.Title = ParseFilename(base+".x", folderArtist, idx).Title
	default:
		out.Title = base
	}

	// Artist: the folder is the grouping artist (album artist when set).
	if folderArtist != UnknownArtist {
		if t.AlbumArtist != "" && strings.EqualFold(SanitizeSegment(t.AlbumArtist), folderArtist) {
			out.AlbumArtist = t.AlbumArtist
			out.Artist = t.Artist
		} else if t.Artist != "" && strings.EqualFold(SanitizeSegment(t.Artist), folderArtist) {
			out.Artist = t.Artist
		} else {
			out.Artist = folderArtist
		}
	}

	if folderAlbum != "" {
		if t.Album != "" && SanitizeSegment(t.Album) == folderAlbum {
			out.Album = t.Album
		} else {
			out.Album = folderAlbum
		}
	}

	if out.Title == "" {
		out.Title = base
	}
	if tagsAgree(t, out) {
		return out, SourceTags
	}
	return out, SourceFilename
}

func tagsAgree(t, out Meta) bool {
	return t.Title != "" && t.Title == out.Title && (t.Artist == out.Artist || t.AlbumArtist == out.AlbumArtist)
}
