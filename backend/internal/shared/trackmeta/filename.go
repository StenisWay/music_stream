package trackmeta

import (
	"html"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// ArtistIndex answers whether a string is a known artist name. It is built
// from tags and folder names of the whole collection so the filename parser
// can decide which side of "A - B" is the artist.
type ArtistIndex interface {
	IsKnownArtist(name string) bool
}

// noisePattern matches decorations that video sites add to titles.
var noisePattern = regexp.MustCompile(`(?i)(official|music\s*video|lyrics?|\bmv\b|m-v|\bhd\b|\bhq\b|\b4k\b|1080p|720p|ncs\s*release|高音質|高音质|完整版|字幕|歌詞|歌词|無浮水印|下載|动态|動態|首播|耳機|女性が歌う|付き|cover(ed)?\s*by|feat\.?|主題歌|主题曲|主題曲|插曲|片尾曲|片頭曲|電影|电影|\bver\b\.?|\bversion\b|\baudio\b|nodrm)`)

var (
	bracketPairs   = [][2]rune{{'【', '】'}, {'[', ']'}, {'《', '》'}, {'『', '』'}, {'「', '」'}, {'(', ')'}, {'（', '）'}}
	leadingTrackNo = regexp.MustCompile(`^(\d{1,3})\s*(?:[.\-_、]\s*|\s+)(\S.*)$`)
	multiDash      = regexp.MustCompile(`-{2,}`)
	trailingParen  = regexp.MustCompile(`^(.+?)\s*\((.+)\)$`)
	byPattern      = regexp.MustCompile(`(?i)^(.+?)\s+by\s+(.+)$`)
	creditPattern  = regexp.MustCompile(`演唱\s*[-:：]\s*(\S+)`)
	inlineNoise    = regexp.MustCompile(`(?i)(official\s*(music\s*)?(video|mv|channel)?|music\s*video|lyrics?\s*video|\bmv\b|\bhd\b|\bhq\b|官方版?|完整版|首播|片尾曲|片頭曲|主題曲|主题曲|插曲|音檔|字幕|歌詞版?|演唱\s*[-:：]\s*\S+|詞\s*[-:：]\s*\S+|曲\s*[-:：]\s*\S+)`)
	trailingNoise  = regexp.MustCompile(`(?i)[\s\-_]*(official\s*(music\s*)?video|official\s*mv|official|lyric\s*video|with\s*lyrics|lyrics?|mv|hd|\d{4})\s*$`)
)

// genrePrefixes are leading words that describe a genre rather than an artist,
// e.g. "國語老歌-張清芳-出塞曲".
var genrePrefixes = map[string]struct{}{
	"國語老歌": {}, "国语老歌": {}, "老歌": {}, "民歌": {}, "經典老歌": {}, "經典": {}, "華語": {},
	"男女對唱": {}, "新兵日記": {}, "國語新歌": {}, "國語": {}, "nodrm": {},
}

// separators in priority order.
var separators = []string{" - ", " － ", " – ", " — ", "--", "－", "–", "—", "-", "～", "~", "ˇ", "_", "|", "｜"}

// ParseFilename infers artist, title and track number from a file name.
// artistHint is an artist implied by the folder layout (may be empty).
func ParseFilename(name, artistHint string, idx ArtistIndex) Meta {
	s := strings.TrimSuffix(name, filepath.Ext(name))
	s = normaliseSpaces(html.UnescapeString(s))

	var m Meta
	if mm := leadingTrackNo.FindStringSubmatch(s); mm != nil {
		if n, _ := strconv.Atoi(mm[1]); n > 0 && n < 200 {
			m.TrackNo, s = n, mm[2]
		}
	}

	credit := creditPattern.FindStringSubmatch(s)
	outside, inside, leading := extractBrackets(s)
	outside = cleanNoise(inlineNoise.ReplaceAllString(outside, " "))

	if credit != nil && len(inside) > 0 {
		// "主題曲【聽見下雨的聲音】完整MV 演唱-魏如昀 詞-方文山"
		m.Artist, m.Title = cleanArtist(credit[1]), cleanTitle(inside[0])
		return finish(m, artistHint)
	}

	switch {
	case len(inside) > 0 && outside == "":
		// "[Title]" with everything else being noise.
		s = inside[0]
	case leading && len(splitParts(outside)) < 2:
		// "【 Artist 】 Title"
		m.Artist, m.Title = cleanArtist(inside[0]), cleanTitle(outside)
		return finish(m, artistHint)
	case len(inside) > 0 && len(splitParts(outside)) < 2:
		// "Artist【Title】Official MV" / "Artist 《Title》" / "五月天 (擁抱MV)"
		m.Artist, m.Title = pickBracketArtist(outside, idx), cleanTitle(inside[0])
		return finish(m, artistHint)
	default:
		s = outside
	}

	if mm := trailingParen.FindStringSubmatch(s); mm != nil && idx != nil && idx.IsKnownArtist(mm[2]) {
		// "溫柔 (五月天)"
		m.Title, m.Artist = cleanTitle(mm[1]), mm[2]
		return finish(m, artistHint)
	}
	if mm := byPattern.FindStringSubmatch(s); mm != nil {
		m.Title, m.Artist = cleanTitle(mm[1]), cleanArtist(mm[2])
		return finish(m, artistHint)
	}

	parts := splitParts(s)
	parts = dropGenrePrefixes(parts)

	switch len(parts) {
	case 0:
		m.Title = cleanTitle(s)
	case 1:
		m.Artist, m.Title = splitBySpace(parts[0], artistHint, idx)
	default:
		m.Artist, m.Title = pickArtistTitle(parts, artistHint, idx, &m.TrackNo)
	}
	return finish(m, artistHint)
}

func finish(m Meta, artistHint string) Meta {
	if m.Artist == "" {
		m.Artist = artistHint
	}
	if m.Title == "" && m.Artist != "" {
		m.Title, m.Artist = m.Artist, artistHint
	}
	m.Title = strings.Trim(m.Title, " -_.")
	m.Artist = strings.Trim(m.Artist, " -_.")
	return m
}

// pickBracketArtist chooses the artist from the text around a bracketed
// title, preferring a known artist token when there is extra text
// ("那英 天后那英睽违五年初心回归" → "那英").
func pickBracketArtist(outside string, idx ArtistIndex) string {
	cleaned := cleanArtist(outside)
	fields := strings.Fields(cleaned)
	if len(fields) > 1 && idx != nil {
		for _, f := range fields {
			if idx.IsKnownArtist(f) {
				return f
			}
		}
	}
	return cleaned
}

// extractBrackets scans s left to right and removes bracketed segments.
// Meaningful (non-noise) contents of square/CJK brackets are returned in
// order; parenthesised non-noise text such as "(off vocal)" stays in place.
// leading reports whether the first meaningful bracket opens the string.
func extractBrackets(s string) (outside string, inside []string, leading bool) {
	var b strings.Builder
	runes := []rune(s)
	for i := 0; i < len(runes); i++ {
		closeRune, isOpen := closingFor(runes[i])
		if !isOpen {
			b.WriteRune(runes[i])
			continue
		}
		j := i + 1
		for j < len(runes) && runes[j] != closeRune {
			j++
		}
		if j == len(runes) { // unbalanced: keep literally
			b.WriteRune(runes[i])
			continue
		}
		content := strings.TrimSpace(string(runes[i+1 : j]))
		isParen := runes[i] == '(' || runes[i] == '（'
		meaningful := content != "" && !noisePattern.MatchString(content)
		if !meaningful && isParen {
			// "(擁抱MV)" → the noise wraps a real title.
			if rest := strings.TrimSpace(inlineNoise.ReplaceAllString(content, "")); rest != "" && isPureCJK(rest) && rest != content {
				content, isParen, meaningful = rest, false, true
			}
		}
		switch {
		case meaningful && isParen:
			b.WriteString(" (" + content + ") ")
		case meaningful:
			if len(inside) == 0 && strings.TrimSpace(b.String()) == "" {
				leading = true
			}
			inside = append(inside, content)
			b.WriteRune(' ')
		default:
			b.WriteRune(' ')
		}
		i = j
	}
	return normaliseSpaces(b.String()), inside, leading
}

func closingFor(r rune) (rune, bool) {
	for _, p := range bracketPairs {
		if p[0] == r {
			return p[1], true
		}
	}
	return 0, false
}

func cleanNoise(s string) string {
	s = multiDash.ReplaceAllString(s, "--")
	for {
		next := strings.TrimSpace(trailingNoise.ReplaceAllString(s, ""))
		if next == s {
			break
		}
		s = next
	}
	return strings.Trim(s, " -_~")
}

func splitParts(s string) []string {
	for _, sep := range separators {
		if !strings.Contains(s, sep) {
			continue
		}
		var parts []string
		for _, p := range splitOn(s, sep) {
			p = strings.TrimSpace(p)
			if p == "" || strings.TrimSpace(noisePattern.ReplaceAllString(p, "")) == "" {
				continue
			}
			parts = append(parts, p)
		}
		if len(parts) >= 2 {
			return parts
		}
	}
	return []string{strings.TrimSpace(s)}
}

// splitOn splits on sep, except that a bare "-" between two ASCII letters is
// part of a name ("A-Lin", "Good-bye").
func splitOn(s, sep string) []string {
	if sep != "-" {
		return strings.Split(s, sep)
	}
	var parts []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] != '-' {
			continue
		}
		if left, right := letterRun(s[:i], true), letterRun(s[i+1:], false); left > 0 && right > 0 &&
			(left <= 2 || (left <= 4 && right <= 4)) {
			continue
		}
		parts = append(parts, s[start:i])
		start = i + 1
	}
	return append(parts, s[start:])
}

func isASCIILetter(c byte) bool { return (c|0x20) >= 'a' && (c|0x20) <= 'z' }

// letterRun counts consecutive ASCII letters at the end (backward) or start
// of s.
func letterRun(s string, backward bool) int {
	n := 0
	for i := range len(s) {
		c := s[i]
		if backward {
			c = s[len(s)-1-i]
		}
		if !isASCIILetter(c) {
			break
		}
		n++
	}
	return n
}

// stripGenrePrefix removes a genre word glued to the artist with a tighter
// separator: "國語老歌-張清芳" → "張清芳".
func stripGenrePrefix(s string) string {
	if parts := dropGenrePrefixes(splitParts(s)); len(parts) == 1 {
		return parts[0]
	}
	return s
}

func dropGenrePrefixes(parts []string) []string {
	for len(parts) > 1 {
		if _, ok := genrePrefixes[parts[0]]; !ok {
			break
		}
		parts = parts[1:]
	}
	return parts
}

func pickArtistTitle(parts []string, hint string, idx ArtistIndex, trackNo *int) (artist, title string) {
	// Pull out bare track numbers ("楊丞琳-Rainie & Love雨愛-05-折疊式愛情").
	var rest []string
	for _, p := range parts {
		p = stripTrailingTrackNo(p, trackNo)
		if n, err := strconv.Atoi(p); err == nil && n > 0 && n < 100 && len(parts) > 2 {
			if *trackNo == 0 {
				*trackNo = n
			}
			continue
		}
		rest = append(rest, p)
	}
	parts = rest
	if len(parts) == 1 {
		return splitBySpace(parts[0], hint, idx)
	}

	known := func(p string) bool {
		c := cleanArtist(p)
		return (hint != "" && (c == hint || strings.HasPrefix(p, hint))) || (idx != nil && idx.IsKnownArtist(c))
	}
	first, last := parts[0], parts[len(parts)-1]
	switch {
	case known(first):
		artist, title = first, last
		if hint != "" && strings.HasPrefix(first, hint) {
			artist = hint
		}
	case known(last) && !known(first):
		artist, title = last, first
	case len(parts) > 2 && known(parts[len(parts)-2]):
		artist, title = parts[len(parts)-2], last
	default:
		artist, title = first, last
	}
	title = stripTrailingTrackNo(title, trackNo)
	return cleanArtist(stripGenrePrefix(artist)), cleanTitle(title)
}

func stripTrailingTrackNo(s string, trackNo *int) string {
	if mm := leadingTrackNo.FindStringSubmatch(s); mm != nil {
		if n, _ := strconv.Atoi(mm[1]); n > 0 && n < 100 {
			if *trackNo == 0 {
				*trackNo = n
			}
			return mm[2]
		}
	}
	return s
}

// splitBySpace handles "周華健 有沒有那麼一首歌" style names that have no
// explicit separator.
func splitBySpace(s, hint string, idx ArtistIndex) (artist, title string) {
	if hint != "" && strings.HasPrefix(s, hint) && len(s) > len(hint) {
		return hint, cleanTitle(strings.TrimLeft(s[len(hint):], " -_ˇ~"))
	}
	fields := strings.Fields(s)
	if len(fields) >= 2 {
		if idx != nil && idx.IsKnownArtist(cleanArtist(fields[0])) {
			return cleanArtist(fields[0]), cleanTitle(strings.Join(fields[1:], " "))
		}
		if idx != nil && idx.IsKnownArtist(cleanArtist(fields[len(fields)-1])) {
			return cleanArtist(fields[len(fields)-1]), cleanTitle(strings.Join(fields[:len(fields)-1], " "))
		}
		// CJK name followed by a Latin title: "王力宏 Forever Love".
		if n := utf8.RuneCountInString(fields[0]); isPureCJK(fields[0]) && n >= 2 && n <= 4 && isASCII(strings.Join(fields[1:], " ")) {
			return fields[0], strings.Join(fields[1:], " ")
		}
		// Two pure-CJK tokens where the first is name-length: "阿桑 葉子".
		if len(fields) == 2 && isPureCJK(fields[0]) && isPureCJK(fields[1]) {
			if n := utf8.RuneCountInString(fields[0]); n >= 2 && n <= 4 {
				return fields[0], fields[1]
			}
		}
	}
	return "", cleanTitle(s)
}

func isPureCJK(s string) bool {
	for _, r := range s {
		if !IsCJK(r) {
			return false
		}
	}
	return s != ""
}

// cleanArtist drops romanised duplicates such as "周華健 Wakin Chau" → "周華健".
func cleanArtist(s string) string {
	s = cleanNoise(normaliseSpaces(s))
	if !containsCJK(s) {
		return s
	}
	var b strings.Builder
	for _, r := range s {
		if r < utf8.RuneSelf && (unicode.IsLetter(r) || r == '.' || r == '\'') {
			continue
		}
		b.WriteRune(r)
	}
	out := normaliseSpaces(strings.Trim(b.String(), " .&+"))
	if out == "" {
		return s
	}
	return out
}

// cleanTitle removes an English translation that follows a CJK title:
// "愛．這件事情 This Thing Called Love" → "愛．這件事情".
func cleanTitle(s string) string {
	s = cleanNoise(normaliseSpaces(s))
	runes := []rune(s)
	if len(runes) == 0 || !IsCJK(runes[0]) {
		return s
	}
	lastCJK := -1
	for i, r := range runes {
		if IsCJK(r) {
			lastCJK = i
		}
	}
	tail := strings.TrimSpace(string(runes[lastCJK+1:]))
	if len(strings.Fields(tail)) >= 2 && isASCII(tail) {
		return strings.TrimSpace(string(runes[:lastCJK+1]))
	}
	// "原來你都在You've Always Been Here"
	if len(strings.Fields(tail)) >= 3 {
		return strings.TrimSpace(string(runes[:lastCJK+1]))
	}
	return s
}

func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] >= utf8.RuneSelf {
			return false
		}
	}
	return true
}

func normaliseSpaces(s string) string {
	s = strings.ReplaceAll(s, "\u3000", " ")
	return collapseSpaces(strings.TrimSpace(s))
}
