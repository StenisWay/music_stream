// Package presentation is the HTTP delivery of the library feature. It also
// exports the track JSON contract and Presenter, which other features
// (playlists) reuse to render tracks consistently.
package presentation

import (
	"context"
	"fmt"

	"musicstream/internal/feature/library/domain"
	"musicstream/internal/platform/httpx"
	"musicstream/internal/shared"
)

// TrackDTO is the JSON contract shared with the Android client.
type TrackDTO struct {
	ID          int64  `json:"id"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`
	Album       string `json:"album"`
	AlbumArtist string `json:"albumArtist"`
	ArtistID    string `json:"artistId"`
	AlbumID     string `json:"albumId,omitempty"`
	TrackNo     int    `json:"trackNo"`
	Year        int    `json:"year"`
	Genre       string `json:"genre"`
	DurationMs  int64  `json:"durationMs"`
	BitrateKbps int    `json:"bitrateKbps"`
	Size        int64  `json:"size"`
	Format      string `json:"format"`
	StreamURL   string `json:"streamUrl"`
	ArtworkURL  string `json:"artworkUrl,omitempty"`
	HasArtwork  bool   `json:"hasArtwork"`
	Playable    bool   `json:"playable"`
	MetaSource  string `json:"metaSource"`
	UserEdited  bool   `json:"userEdited"`
	AddedAt     int64  `json:"addedAt"`
	Type        string `json:"type,omitempty"`   // 歌曲 | 純音樂 (library folder)
	Region      string `json:"region,omitempty"` // 華語 | 日本 | 韓國 | 歐美 | 其他
}

// CategoryDTO is one <Type>/<Region>/ folder of the library.
type CategoryDTO struct {
	Type        string `json:"type"`
	Region      string `json:"region"`
	TrackCount  int    `json:"trackCount"`
	ArtistCount int    `json:"artistCount"`
}

type ArtistDTO struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	TrackCount int    `json:"trackCount"`
	AlbumCount int    `json:"albumCount"`
	ArtworkURL string `json:"artworkUrl,omitempty"`
}

type AlbumDTO struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	ArtistID   string `json:"artistId"`
	Year       int    `json:"year"`
	TrackCount int    `json:"trackCount"`
	DurationMs int64  `json:"durationMs"`
	ArtworkURL string `json:"artworkUrl,omitempty"`
}

// PageDTO is the paginated list envelope.
type PageDTO[T any] struct {
	Items  []T `json:"items"`
	Total  int `json:"total"`
	Limit  int `json:"limit"`
	Offset int `json:"offset"`
}

func ToPage[S, T any](res shared.PageResult[S], conv func(S) T) PageDTO[T] {
	items := make([]T, 0, len(res.Items))
	for _, s := range res.Items {
		items = append(items, conv(s))
	}
	return PageDTO[T]{Items: items, Total: res.Total, Limit: res.Page.Limit, Offset: res.Page.Offset}
}

func ToArtistDTO(a domain.Artist) ArtistDTO {
	return ArtistDTO{ID: string(a.ID), Name: a.Name, TrackCount: a.TrackCount, AlbumCount: a.AlbumCount, ArtworkURL: artworkURL(a.ArtworkTrackID)}
}

func ToAlbumDTO(a domain.Album) AlbumDTO {
	return AlbumDTO{
		ID: string(a.ID), Title: a.Title, Artist: a.Artist, ArtistID: string(a.ArtistID), Year: a.Year,
		TrackCount: a.TrackCount, DurationMs: a.Duration.Milliseconds(), ArtworkURL: artworkURL(a.ArtworkTrackID),
	}
}

func artworkURL(id domain.TrackID) string {
	if id == 0 {
		return ""
	}
	return fmt.Sprintf("/api/v1/tracks/%d/artwork", id)
}

// Presenter renders tracks with the right stream URL (direct or transcoded).
type Presenter struct {
	CanTranscode func() bool
}

func (p Presenter) Track(t domain.Track) TrackDTO {
	streamURL := fmt.Sprintf("/api/v1/tracks/%d/stream", t.ID)
	playable := t.Format.NativelyPlayableOnAndroid()
	if !playable && p.CanTranscode != nil && p.CanTranscode() {
		streamURL += "?transcode=mp3"
		playable = true
	}
	art := domain.TrackID(0)
	if t.HasArtwork {
		art = t.ID
	}
	cat := t.Category()
	return TrackDTO{
		Type: cat.Type, Region: cat.Region,
		ID: int64(t.ID), Title: t.Title, Artist: t.Artist, Album: t.Album, AlbumArtist: t.AlbumArtist,
		ArtistID: string(t.ArtistID()), AlbumID: string(t.AlbumID()), TrackNo: t.TrackNo, Year: t.Year, Genre: t.Genre,
		DurationMs: t.Duration.Milliseconds(), BitrateKbps: t.BitrateKbps, Size: t.Size, Format: string(t.Format),
		StreamURL: streamURL, ArtworkURL: artworkURL(art), HasArtwork: t.HasArtwork,
		Playable: playable, MetaSource: string(t.MetaSource), UserEdited: t.UserEdited,
		AddedAt: httpx.Millis(t.AddedAt),
	}
}

// Tracks converts tracks. The error is kept for callers' convenience; it is
// always nil.
func (p Presenter) Tracks(_ context.Context, tracks []domain.Track) ([]TrackDTO, error) {
	out := make([]TrackDTO, len(tracks))
	for i, t := range tracks {
		out[i] = p.Track(t)
	}
	return out, nil
}

// TrackPage converts a page of tracks.
func (p Presenter) TrackPage(ctx context.Context, res shared.PageResult[domain.Track]) (PageDTO[TrackDTO], error) {
	items, err := p.Tracks(ctx, res.Items)
	if err != nil {
		return PageDTO[TrackDTO]{}, err
	}
	return PageDTO[TrackDTO]{Items: items, Total: res.Total, Limit: res.Page.Limit, Offset: res.Page.Offset}, nil
}
