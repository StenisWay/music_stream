package audiotag

import (
	"encoding/binary"
	"errors"
	"io"
	"time"
)

// MP3 duration without decoding: locate the first MPEG audio frame, then use
// the Xing/Info or VBRI header frame count (VBR), or fall back to
// size/bitrate (CBR). This reads only a few KB per file.

var errNoFrame = errors.New("no mpeg frame found")

var (
	// bitrates[version][layer][index] in kbps; version 0 = MPEG1, 1 = MPEG2/2.5.
	bitrates = [2][3][16]int{
		{
			{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
			{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
			{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
		},
		{
			{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
		},
	}
	sampleRates = [4][3]int{{11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}}
)

type frameHeader struct {
	mpeg1         bool
	layer         int // 1..3
	bitrateKbps   int
	sampleRate    int
	mono          bool
	samplesPerFrm int
}

func parseFrameHeader(b []byte) (frameHeader, bool) {
	if len(b) < 4 || b[0] != 0xFF || b[1]&0xE0 != 0xE0 {
		return frameHeader{}, false
	}
	versionBits := (b[1] >> 3) & 0x03 // 0: 2.5, 2: 2, 3: 1
	layerBits := (b[1] >> 1) & 0x03   // 1: III, 2: II, 3: I
	brIndex := b[2] >> 4
	srIndex := (b[2] >> 2) & 0x03
	if versionBits == 1 || layerBits == 0 || brIndex == 0 || brIndex == 15 || srIndex == 3 {
		return frameHeader{}, false
	}
	h := frameHeader{mpeg1: versionBits == 3, layer: 4 - int(layerBits)}
	vi := 1
	if h.mpeg1 {
		vi = 0
	}
	h.bitrateKbps = bitrates[vi][h.layer-1][brIndex]
	h.sampleRate = sampleRates[versionBits][srIndex]
	h.mono = (b[3] >> 6) == 3
	switch {
	case h.layer == 1:
		h.samplesPerFrm = 384
	case h.layer == 3 && !h.mpeg1:
		h.samplesPerFrm = 576
	default:
		h.samplesPerFrm = 1152
	}
	return h, h.sampleRate > 0 && h.bitrateKbps > 0
}

// id3v2Size returns the size of a leading ID3v2 tag (0 if none).
func id3v2Size(b []byte) int64 {
	if len(b) < 10 || string(b[:3]) != "ID3" {
		return 0
	}
	size := int64(b[6]&0x7F)<<21 | int64(b[7]&0x7F)<<14 | int64(b[8]&0x7F)<<7 | int64(b[9]&0x7F)
	size += 10
	if b[5]&0x10 != 0 { // footer present
		size += 10
	}
	return size
}

// mp3Duration estimates the duration and average bitrate of an MP3 stream.
func mp3Duration(r io.ReadSeeker, fileSize int64) (time.Duration, int, error) {
	head := make([]byte, 10)
	if _, err := io.ReadFull(r, head); err != nil {
		return 0, 0, err
	}
	start := id3v2Size(head)

	// Search the first valid frame within 64 KiB after the tag.
	buf := make([]byte, 64<<10)
	if _, err := r.Seek(start, io.SeekStart); err != nil {
		return 0, 0, err
	}
	n, err := io.ReadFull(r, buf)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) {
		return 0, 0, err
	}
	buf = buf[:n]
	for i := 0; i+4 <= len(buf); i++ {
		h, ok := parseFrameHeader(buf[i:])
		if !ok {
			continue
		}
		frame := buf[i:]
		audioBytes := fileSize - start - int64(i)
		if frames := vbrFrameCount(frame, h); frames > 0 {
			secs := float64(frames) * float64(h.samplesPerFrm) / float64(h.sampleRate)
			d := time.Duration(secs * float64(time.Second))
			kbps := 0
			if secs > 0 {
				kbps = int(float64(audioBytes) * 8 / secs / 1000)
			}
			return d, kbps, nil
		}
		if hasID3v1(r, fileSize) {
			audioBytes -= 128
		}
		secs := float64(audioBytes) * 8 / float64(h.bitrateKbps*1000)
		return time.Duration(secs * float64(time.Second)), h.bitrateKbps, nil
	}
	return 0, 0, errNoFrame
}

// vbrFrameCount reads the Xing/Info or VBRI header in the first frame.
func vbrFrameCount(frame []byte, h frameHeader) uint32 {
	// Xing offset depends on version and channel mode.
	off := 4 + 32
	switch {
	case h.mpeg1 && h.mono:
		off = 4 + 17
	case !h.mpeg1 && !h.mono:
		off = 4 + 17
	case !h.mpeg1 && h.mono:
		off = 4 + 9
	}
	if len(frame) >= off+12 {
		tag := string(frame[off : off+4])
		if tag == "Xing" || tag == "Info" {
			flags := binary.BigEndian.Uint32(frame[off+4:])
			if flags&0x1 != 0 {
				return binary.BigEndian.Uint32(frame[off+8:])
			}
		}
	}
	if len(frame) >= 4+32+18 && string(frame[36:40]) == "VBRI" {
		return binary.BigEndian.Uint32(frame[36+14:])
	}
	return 0
}

func hasID3v1(r io.ReadSeeker, size int64) bool {
	if size < 128 {
		return false
	}
	tag := make([]byte, 3)
	if _, err := r.Seek(size-128, io.SeekStart); err != nil {
		return false
	}
	if _, err := io.ReadFull(r, tag); err != nil {
		return false
	}
	return string(tag) == "TAG"
}
