package infrastructure

import "musicstream/internal/platform/filehash"

// FileHasher implements application.Hasher with the same SHA-256 the ingest
// feature uses, so content hashes are comparable across features.
type FileHasher struct{}

func (FileHasher) Hash(absPath string) (string, error) { return filehash.SHA256(absPath) }
