package shared

// UserID identifies an account. It lives in the shared kernel because many
// features (playlists, auth) scope data by user.
type UserID int64
