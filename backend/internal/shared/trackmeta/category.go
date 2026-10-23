package trackmeta

import (
	"path"
	"regexp"
	"strings"
	"unicode"
)

// The organised library has two classification levels above the artist:
//
//	<Type>/<Region>/<Artist>/[<Album>/]<Title>.ext
//	歌曲/華語/五月天/倔強.mp3
//	純音樂/日本/CLANNAD/CLANNAD OST DISC1/渚.mp3
const (
	TypeSong         = "歌曲"  // has vocals
	TypeInstrumental = "純音樂" // no vocals: soundtracks, piano, off vocal…

	RegionChinese  = "華語"
	RegionJapanese = "日本"
	RegionKorean   = "韓國"
	RegionWestern  = "歐美"
	RegionOther    = "其他"
)

var (
	types   = map[string]bool{TypeSong: true, TypeInstrumental: true}
	regions = map[string]bool{RegionChinese: true, RegionJapanese: true, RegionKorean: true, RegionWestern: true, RegionOther: true}
)

// Category is the type/region pair a track is filed under.
type Category struct {
	Type   string
	Region string
}

// Dir is the relative folder of the category inside the library.
func (c Category) Dir() string { return c.Type + "/" + c.Region }

// Valid reports whether both levels are known values.
func (c Category) Valid() bool { return types[c.Type] && regions[c.Region] }

// SplitCategory strips the leading <Type>/<Region>/ from a library-relative
// path. ok is false (and rest == relPath) for paths outside the two levels,
// such as files of the old <Artist>/… layout.
func SplitCategory(relPath string) (c Category, rest string, ok bool) {
	parts := strings.SplitN(strings.ReplaceAll(relPath, "\\", "/"), "/", 3)
	if len(parts) == 3 {
		c = Category{Type: parts[0], Region: parts[1]}
		if c.Valid() {
			return c, parts[2], true
		}
	}
	return Category{}, relPath, false
}

// instrumentalPattern matches titles, albums and genres of tracks without
// vocals. Soundtrack/OST alone is not enough: those albums mix songs and BGM.
var instrumentalPattern = regexp.MustCompile(`(?i)(instrumental|\(inst\)|\binst\.?$|off[\s_-]*vocal|karaoke|\(bonus kara\)|伴奏|純音樂|纯音乐|輕音樂|轻音乐|鋼琴|钢琴|piano|orgel|oegel|オルゴール|八音盒|音樂盒|music\s?box|水晶音樂|\bbgm\b|accordion ver|-guitar-|-sax-|陶笛|ocarina|intro only|^intro$|^classical$|^new age$)`)

// IsInstrumental reports whether any of the given strings (title, file
// name, album, genre…) marks the track as instrumental.
func IsInstrumental(values ...string) bool {
	for _, v := range values {
		if v != "" && instrumentalPattern.MatchString(strings.TrimSpace(v)) {
			return true
		}
	}
	return false
}

// GuessRegion guesses the language region from the script of the given
// strings (artist first). Kana anywhere means Japanese (Japanese artists
// often have kanji-only names) and hangul Korean; otherwise the first
// string with letters decides: Han characters Chinese, Latin Western.
func GuessRegion(values ...string) string {
	for _, v := range values {
		for _, r := range v {
			switch {
			case unicode.In(r, unicode.Hiragana, unicode.Katakana):
				return RegionJapanese
			case unicode.Is(unicode.Hangul, r):
				return RegionKorean
			}
		}
	}
	for _, v := range values {
		for _, r := range v {
			switch {
			case unicode.Is(unicode.Han, r):
				return RegionChinese
			case r < unicode.MaxASCII && unicode.IsLetter(r):
				return RegionWestern
			}
		}
	}
	return RegionOther
}

// ArtistCategories remembers where each artist folder already lives, so new
// tracks join the artist's existing type/region instead of being guessed.
type ArtistCategories map[string]map[Category]int

// Add records one library-relative track path.
func (a ArtistCategories) Add(relPath string) {
	c, rest, ok := SplitCategory(relPath)
	if !ok || path.Dir(rest) == "." {
		return
	}
	artist := strings.ToLower(strings.SplitN(rest, "/", 2)[0])
	if a[artist] == nil {
		a[artist] = map[Category]int{}
	}
	a[artist][c]++
}

// Lookup returns the artist's most common category.
func (a ArtistCategories) Lookup(artistFolder string) (Category, bool) {
	var best Category
	n := 0
	for c, count := range a[strings.ToLower(artistFolder)] {
		if count > n || (count == n && c.Dir() < best.Dir()) {
			best, n = c, count
		}
	}
	return best, n > 0
}

// Classify picks the category of a new track. A known artist keeps its
// region (and its type unless the track itself is marked instrumental);
// otherwise both levels are guessed from the metadata.
func Classify(m Meta, fileName, artistFolder string, known ArtistCategories) Category {
	instrumental := IsInstrumental(m.Title, strings.TrimSuffix(fileName, path.Ext(fileName)), m.Album, m.Genre)
	if c, ok := known.Lookup(artistFolder); ok {
		if instrumental {
			c.Type = TypeInstrumental
		}
		return c
	}
	c := Category{Type: TypeSong, Region: GuessRegion(m.GroupingArtist(), m.Title, m.Album)}
	if m.GroupingArtist() == UnknownArtist {
		c.Region = GuessRegion(m.Title, m.Album)
	}
	if instrumental {
		c.Type = TypeInstrumental
	}
	return c
}
