package audiotag

import (
	"musicstream/internal/shared/trackmeta"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/encoding"
	"golang.org/x/text/encoding/japanese"
	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/traditionalchinese"
)

// Legacy ID3 tags written by Windows-era Taiwanese/Chinese/Japanese software
// store Big5 / GBK / Shift-JIS bytes while declaring ISO-8859-1. The tag
// library therefore hands us one rune per byte ("³Ì¦nªºµ²§½"). We recover
// the raw bytes and try each plausible codepage, scoring the candidates by
// how well they agree with the file path (which is usually correct UTF-8).

type codepage struct {
	name string
	enc  encoding.Encoding // nil means UTF-8
	// common reports whether a double-byte lead byte falls in the range of
	// frequently used characters (Big5 level 1, GB2312 level 1, JIS level 1
	// + kana). It disambiguates byte strings that decode validly in several
	// codepages.
	common func(lead byte) bool
}

var codepages = []codepage{
	{"utf-8", nil, nil},
	{"big5", traditionalchinese.Big5, func(b byte) bool { return b >= 0xA4 && b <= 0xC6 }},
	{"gbk", simplifiedchinese.GBK, func(b byte) bool { return b >= 0xB0 && b <= 0xD7 }},
	{"shift_jis", japanese.ShiftJIS, func(b byte) bool { return b == 0x82 || b == 0x83 || (b >= 0x88 && b <= 0x98) }},
	{"euc-jp", japanese.EUCJP, func(b byte) bool { return b == 0xA4 || b == 0xA5 || (b >= 0xB0 && b <= 0xCF) }},
}

// commonness counts double-byte sequences whose lead byte is in the
// codepage's common-character range. UTF-8 is always considered common.
func commonness(raw []byte, cp codepage) int {
	n := 0
	for i := 0; i < len(raw); i++ {
		if raw[i] < 0x80 {
			continue
		}
		if cp.common == nil || cp.common(raw[i]) {
			n++
		}
		i++ // skip trail byte
	}
	return n
}

// RepairText returns s decoded with the most plausible legacy codepage, or s
// unchanged if it does not look like mis-decoded bytes. ok=false means s
// looks like mojibake that could not be repaired and should be discarded.
func RepairText(s, pathHint string) (string, bool) {
	raw, isByteString := latin1Bytes(s)
	if !isByteString {
		if fixed, ok := repairDoubleEncoded(s); ok {
			return fixed, true
		}
		return s, !looksGarbled(s)
	}
	if !hasHighBytes(raw) {
		return s, true
	}

	best, bestScore := "", 0
	for _, cp := range codepages {
		cand, ok := decode(raw, cp)
		if !ok {
			continue
		}
		score := plausibility(cand, pathHint)
		if score > 0 {
			score += 2 * commonness(raw, cp)
		}
		if score > bestScore {
			best, bestScore = cand, score
		}
	}
	if best != "" {
		return best, true
	}
	// Second chance: tolerate a few lost bytes, but require agreement with
	// the path so we do not invent text.
	for _, cp := range codepages[1:] {
		cand, bad := decodeLenient(raw, cp)
		if bad == 0 || bad*5 > utf8.RuneCountInString(cand) {
			continue
		}
		if score := plausibility(cand, pathHint); score > bestScore && overlapRatio(cand, pathHint) >= 0.5 {
			best, bestScore = cand, score
		}
	}
	if best != "" {
		return best, true
	}
	// Genuine Latin-1 text such as "Beyoncé" stays as is.
	return s, !looksGarbled(s)
}

// repairDoubleEncoded fixes UTF-8 bytes that were decoded as a legacy
// codepage and stored as Unicode ("蜷帙→螟上ヵ繧ｧ繧ｹ" → "君と夏フェス").
// Legit CJK text re-encoded to a legacy codepage essentially never forms
// valid multi-byte UTF-8, so a successful round trip is a strong signal.
func repairDoubleEncoded(s string) (string, bool) {
	for _, cp := range codepages[1:] {
		raw, err := cp.enc.NewEncoder().Bytes([]byte(s))
		if err != nil || !utf8.Valid(raw) || !hasHighBytes(raw) {
			continue
		}
		if out := string(raw); out != s && plausibility(out, "") > 0 {
			return out, true
		}
	}
	return "", false
}

func latin1Bytes(s string) ([]byte, bool) {
	b := make([]byte, 0, len(s))
	for _, r := range s {
		if r > 0xFF {
			return nil, false
		}
		b = append(b, byte(r))
	}
	return b, true
}

func hasHighBytes(b []byte) bool {
	for _, c := range b {
		if c >= 0x80 {
			return true
		}
	}
	return false
}

func decode(raw []byte, cp codepage) (string, bool) {
	var out string
	if cp.enc == nil {
		if !utf8.Valid(raw) {
			return "", false
		}
		out = string(raw)
	} else {
		dec, err := cp.enc.NewDecoder().Bytes(raw)
		if err != nil {
			return "", false
		}
		out = string(dec)
	}
	for _, r := range out {
		if r == utf8.RuneError || (unicode.IsControl(r) && r != '\t') || unicode.In(r, unicode.Co) {
			return "", false
		}
	}
	return out, true
}

// decodeLenient decodes byte by byte, replacing undecodable sequences. It is
// used as a fallback for tags where the tagger already lost a byte.
func decodeLenient(raw []byte, cp codepage) (string, int) {
	if cp.enc == nil {
		return "", len(raw)
	}
	var b strings.Builder
	bad := 0
	for i := 0; i < len(raw); {
		if raw[i] < 0x80 {
			b.WriteByte(raw[i])
			i++
			continue
		}
		if i+1 < len(raw) {
			if out, err := cp.enc.NewDecoder().Bytes(raw[i : i+2]); err == nil {
				r, _ := utf8.DecodeRune(out)
				if r != utf8.RuneError && utf8.RuneCount(out) == 1 && !unicode.In(r, unicode.Co) {
					b.Write(out)
					i += 2
					continue
				}
			}
		}
		bad++
		i++
	}
	return b.String(), bad
}

// plausibility scores a decoded candidate; 0 means implausible.
func plausibility(s, pathHint string) int {
	total, cjk, ascii, overlap := 0, 0, 0, 0
	for _, r := range s {
		total++
		switch {
		case r < utf8.RuneSelf:
			ascii++
		case trackmeta.IsCJK(r):
			cjk++
			if strings.ContainsRune(pathHint, r) {
				overlap++
			}
		case unicode.In(r, unicode.Hangul), r >= 0xFF61 && r <= 0xFF9F: // half-width katakana
			return 0
		}
	}
	if total == 0 || cjk == 0 || (cjk+ascii)*10 < total*8 {
		return 0
	}
	return 1 + cjk + overlap*5
}

func overlapRatio(s, hint string) float64 {
	cjk, hit := 0, 0
	for _, r := range s {
		if trackmeta.IsCJK(r) {
			cjk++
			if strings.ContainsRune(hint, r) {
				hit++
			}
		}
	}
	if cjk == 0 {
		return 0
	}
	return float64(hit) / float64(cjk)
}

// looksGarbled flags strings dominated by Latin-1 supplement symbols, or by
// half-width katakana mixed with CJK punctuation (another common mis-decode).
func looksGarbled(s string) bool {
	total, suspicious := 0, 0
	for _, r := range s {
		total++
		if (r >= 0x80 && r <= 0xBF) || r == 0xD7 || r == 0xF7 || (r >= 0xFF61 && r <= 0xFF9F) || r == utf8.RuneError {
			suspicious++
		}
	}
	return total > 0 && suspicious*4 >= total && suspicious >= 2
}
