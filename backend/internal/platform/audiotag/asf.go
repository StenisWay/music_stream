package audiotag

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"time"
	"unicode/utf16"
)

// Minimal ASF (WMA) header reader: duration, bitrate and the Content
// Description object (title/author). The tag library does not support ASF.

var (
	asfHeaderGUID      = guid("75B22630-668E-11CF-A6D9-00AA0062CE6C")
	asfFilePropsGUID   = guid("8CABDCA1-A947-11CF-8EE4-00C00C205365")
	asfContentDescGUID = guid("75B22633-668E-11CF-A6D9-00AA0062CE6C")
	errNotASF          = errors.New("not an ASF file")
)

// guid converts the textual GUID to its little-endian on-disk layout.
func guid(s string) [16]byte {
	var raw [16]byte
	hex := bytes.ReplaceAll([]byte(s), []byte("-"), nil)
	for i := 0; i < 16; i++ {
		raw[i] = fromHex(hex[2*i])<<4 | fromHex(hex[2*i+1])
	}
	var g [16]byte
	g[0], g[1], g[2], g[3] = raw[3], raw[2], raw[1], raw[0]
	g[4], g[5] = raw[5], raw[4]
	g[6], g[7] = raw[7], raw[6]
	copy(g[8:], raw[8:])
	return g
}

func fromHex(c byte) byte {
	switch {
	case c >= '0' && c <= '9':
		return c - '0'
	case c >= 'a' && c <= 'f':
		return c - 'a' + 10
	default:
		return c - 'A' + 10
	}
}

type asfInfo struct {
	duration    time.Duration
	bitrateKbps int
	title       string
	author      string
}

func readASF(r io.Reader) (asfInfo, error) {
	var hdr [30]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return asfInfo{}, err
	}
	if !bytes.Equal(hdr[:16], asfHeaderGUID[:]) {
		return asfInfo{}, errNotASF
	}
	headerSize := binary.LittleEndian.Uint64(hdr[16:24])
	count := binary.LittleEndian.Uint32(hdr[24:28])
	if headerSize > 4<<20 {
		return asfInfo{}, errNotASF
	}
	body := make([]byte, headerSize-30)
	if _, err := io.ReadFull(r, body); err != nil {
		return asfInfo{}, err
	}

	var info asfInfo
	for i, off := uint32(0), uint64(0); i < count && off+24 <= uint64(len(body)); i++ {
		id := body[off : off+16]
		size := binary.LittleEndian.Uint64(body[off+16 : off+24])
		if size < 24 || off+size > uint64(len(body)) {
			break
		}
		obj := body[off+24 : off+size]
		switch {
		case bytes.Equal(id, asfFilePropsGUID[:]) && len(obj) >= 80:
			// FileID(16) FileSize(8) Created(8) Packets(8) PlayDuration(8) SendDuration(8) Preroll(8) Flags(4) MinPkt(4) MaxPkt(4) MaxBitrate(4)
			play := binary.LittleEndian.Uint64(obj[40:48])    // 100ns units
			preroll := binary.LittleEndian.Uint64(obj[56:64]) // ms
			d := time.Duration(play)*100 - time.Duration(preroll)*time.Millisecond
			if d > 0 {
				info.duration = d
			}
			info.bitrateKbps = int(binary.LittleEndian.Uint32(obj[76:80]) / 1000)
		case bytes.Equal(id, asfContentDescGUID[:]) && len(obj) >= 10:
			lens := [5]int{}
			for j := range lens {
				lens[j] = int(binary.LittleEndian.Uint16(obj[2*j:]))
			}
			p := 10
			fields := make([]string, 2)
			for j := 0; j < 2 && p+lens[j] <= len(obj); j++ {
				fields[j] = utf16le(obj[p : p+lens[j]])
				p += lens[j]
			}
			info.title, info.author = fields[0], fields[1]
		}
		off += size
	}
	return info, nil
}

func utf16le(b []byte) string {
	u := make([]uint16, 0, len(b)/2)
	for i := 0; i+1 < len(b); i += 2 {
		v := binary.LittleEndian.Uint16(b[i:])
		if v == 0 {
			break
		}
		u = append(u, v)
	}
	return string(utf16.Decode(u))
}
