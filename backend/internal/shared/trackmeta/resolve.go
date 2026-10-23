package trackmeta

import (
	"path/filepath"
	"regexp"
	"strings"
)

// genericDirPattern matches folder names that carry no artist/album meaning
// (dates, "new folder", catch-all buckets…).
var genericDirPattern = regexp.MustCompile(`(?i)^(\d+|new\d*\]?|新|新歌|新增資料夾.*|new folder.*|music|音樂\d*|未知的專輯|unknown album|其他|我的歌|night|\d|再び呟き|シロ|ラジオ|付き合わせ|scan|bk|booklet|osu|library|inbox|dl|downloads?|下載|下载|youtube|yt|temp|tmp|music\s*download|新資料夾|未命名資料夾)$`)

// categoryDirs are folders whose direct children are named after artists.
var categoryDirs = map[string]struct{}{
	"中文": {}, "日文": {}, "英文": {}, "英文歌": {}, "樂曲": {},
}

// genreDirs are folders that describe a genre/language only.
var genreDirs = map[string]struct{}{
	"中文": {}, "日文": {}, "英文": {}, "英文歌": {}, "樂曲": {}, "動畫": {}, "古典": {},
	"純音樂": {}, "純音樂2": {}, "纯音乐": {},
	TypeSong: {}, RegionChinese: {}, RegionJapanese: {}, RegionKorean: {}, RegionWestern: {}, RegionOther: {},
}

// FolderHints are artist/album guesses derived from a file's directory chain.
type FolderHints struct {
	Artist string
	Album  string
}

// HintsFromPath inspects the directory components of relPath (relative to
// the collection root).
func HintsFromPath(relPath string) FolderHints {
	dirs := strings.Split(filepath.ToSlash(filepath.Dir(relPath)), "/")
	var h FolderHints
	for i, d := range dirs {
		if isGenericDir(d) {
			continue
		}
		if i > 0 {
			if _, ok := categoryDirs[dirs[i-1]]; ok {
				h.Artist = d
				continue
			}
		}
		h.Album = d // deepest meaningful folder wins
	}
	if h.Album == h.Artist {
		h.Album = ""
	}
	return h
}

func isGenericDir(d string) bool {
	if _, ok := genreDirs[d]; ok {
		return true
	}
	return d == "" || d == "." || genericDirPattern.MatchString(strings.TrimSpace(d))
}

var tagTrackPrefix = regexp.MustCompile(`^\d{1,3}\s*[.\-_]\s*`)

// Resolve merges embedded tags with information inferred from the path.
// Tags win when they look trustworthy; the filename fills the gaps.
func Resolve(tags Meta, relPath string, idx ArtistIndex) (Meta, Source) {
	hints := HintsFromPath(relPath)
	fromName := ParseFilename(filepath.Base(relPath), hints.Artist, idx)

	out := sanitizeTags(tags)
	usedFilename, usedTags := false, false

	if out.Title == "" || titleIsAlbumEcho(out, fromName) {
		out.Title = fromName.Title
		usedFilename = true
	} else {
		usedTags = true
	}
	if out.Artist == "" {
		out.Artist = fromName.Artist
		usedFilename = usedFilename || out.Artist != ""
	} else {
		usedTags = true
	}
	if out.TrackNo == 0 {
		out.TrackNo = fromName.TrackNo
	}
	if out.Album == "" {
		out.Album = hints.Album
	}
	if out.Artist == "" && out.AlbumArtist == "" {
		// Instrumental folders such as "宮崎駿" act as the grouping artist.
		out.Artist = hints.Album
		if out.Artist != "" {
			out.Album = ""
		}
	}
	if out.Title == "" {
		out.Title = strings.TrimSuffix(filepath.Base(relPath), filepath.Ext(relPath))
	}

	switch {
	case usedTags && usedFilename:
		return out, SourceMixed
	case usedTags:
		return out, SourceTags
	default:
		return out, SourceFilename
	}
}

var (
	channelArtist = regexp.MustCompile(`(?i)(channel|official|music$|records?$|entertainment|vevo|頻道|官方)`)
	letterPattern = regexp.MustCompile(`[\p{L}\p{N}]`)
)

// sanitizeTags drops tag values that are known to be unreliable: YouTube
// channel names as artists, video-style titles, and lossy values where the
// tagger already replaced characters with '?'.
func sanitizeTags(t Meta) Meta {
	if channelArtist.MatchString(t.Artist) {
		t.Artist = ""
	}
	if channelArtist.MatchString(t.AlbumArtist) {
		t.AlbumArtist = ""
	}
	if strings.ContainsAny(t.Album, "?\uFFFD") {
		t.Album = ""
	}
	title := tagTrackPrefix.ReplaceAllString(t.Title, "")
	for _, prefix := range []string{t.Artist, t.Album} {
		if prefix == "" {
			continue
		}
		if rest, ok := strings.CutPrefix(title, prefix); ok && rest != "" {
			if trimmed := strings.TrimLeft(rest, " -_~ˇ"); trimmed != rest {
				title = tagTrackPrefix.ReplaceAllString(trimmed, "")
			}
		}
	}
	if strings.ContainsAny(title, "?\uFFFD") || noisePattern.MatchString(title) || !letterPattern.MatchString(title) {
		title = ""
	}
	t.Title = title
	return t
}

// titleIsAlbumEcho detects rippers that copy the album name into the title
// field ("BLACK★ROCK SHOOTER" for every track of that album).
func titleIsAlbumEcho(tags, fromName Meta) bool {
	if tags.Album == "" || fromName.Title == "" {
		return false
	}
	return strings.HasPrefix(tags.Album, tags.Title) && !strings.Contains(fromName.Title, tags.Title)
}
