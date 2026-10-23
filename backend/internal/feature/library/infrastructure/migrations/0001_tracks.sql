-- Tracks: one row per audio file. Artists/albums are aggregated views.
CREATE TABLE IF NOT EXISTS tracks (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    path          TEXT    NOT NULL UNIQUE,
    title         TEXT    NOT NULL,
    artist        TEXT    NOT NULL DEFAULT '',
    album         TEXT    NOT NULL DEFAULT '',
    album_artist  TEXT    NOT NULL DEFAULT '',
    grouping_artist TEXT  NOT NULL,
    artist_id     TEXT    NOT NULL,
    album_id      TEXT    NOT NULL DEFAULT '',
    track_no      INTEGER NOT NULL DEFAULT 0,
    year          INTEGER NOT NULL DEFAULT 0,
    genre         TEXT    NOT NULL DEFAULT '',
    duration_ms   INTEGER NOT NULL DEFAULT 0,
    bitrate_kbps  INTEGER NOT NULL DEFAULT 0,
    size          INTEGER NOT NULL,
    mod_time      INTEGER NOT NULL, -- unix nanoseconds
    format        TEXT    NOT NULL,
    content_hash  TEXT    NOT NULL DEFAULT '',
    has_artwork   INTEGER NOT NULL DEFAULT 0,
    meta_source   TEXT    NOT NULL DEFAULT '',
    user_edited   INTEGER NOT NULL DEFAULT 0,
    search_text   TEXT    NOT NULL, -- lower(title || artist || album) for LIKE search
    added_at      INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tracks_artist_id ON tracks(artist_id);
CREATE INDEX IF NOT EXISTS idx_tracks_album_id  ON tracks(album_id);
CREATE INDEX IF NOT EXISTS idx_tracks_hash      ON tracks(content_hash);
CREATE INDEX IF NOT EXISTS idx_tracks_title     ON tracks(title COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_tracks_added     ON tracks(added_at DESC);
