// Package application holds the ingest use cases: planning how messy files
// map onto the <Artist>/<Album>/<Title> layout, executing (and undoing) the
// plan, and the server's automatic inbox ingest. All file access goes
// through the ports in ports.go.
package application

import (
	"context"
	"fmt"
	"musicstream/internal/feature/ingest/domain"
	"musicstream/internal/shared/trackmeta"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// PlanOptions tune planning for incremental ingest into an existing library.
type PlanOptions struct {
	// KnownArtists (e.g. from the catalog) help parse "Artist - Title" names.
	KnownArtists []string
	// KnownContent reports whether a content hash is already in the library;
	// such files are treated as duplicates. Nil disables the check.
	KnownContent func(hash string) bool
	// MinAge skips files modified more recently (still being copied or
	// downloaded); they are picked up by a later run.
	MinAge time.Duration
	Now    func() time.Time
}

// Planner builds a domain.Plan without touching any file.
type Planner struct {
	fs     FileSystem
	reader MetaReader
}

func NewPlanner(fs FileSystem, reader MetaReader) *Planner {
	return &Planner{fs: fs, reader: reader}
}

type audioItem struct {
	entry FileEntry
	tags  trackmeta.Meta
}

func (p *Planner) Plan(ctx context.Context, l domain.Layout, opts PlanOptions) (domain.Plan, error) {
	files, err := p.fs.ListFiles(ctx, l.SourceRoot)
	if err != nil {
		return domain.Plan{}, fmt.Errorf("list source files: %w", err)
	}
	now := time.Now
	if opts.Now != nil {
		now = opts.Now
	}

	var audio []FileEntry
	var others []FileEntry
	for _, f := range files {
		if domain.IsIgnored(f.Path) {
			continue
		}
		if opts.MinAge > 0 && now().Sub(f.ModTime) < opts.MinAge {
			continue
		}
		if domain.IsAudio(f.Path) {
			audio = append(audio, f)
		} else {
			others = append(others, f)
		}
	}

	var plan domain.Plan
	unique, dups, err := p.splitDuplicates(ctx, audio, opts.KnownContent)
	if err != nil {
		return domain.Plan{}, err
	}
	for _, d := range dups {
		plan.Moves = append(plan.Moves, domain.Move{Kind: domain.KindDuplicate, From: d.Path, To: filepath.Join(l.DuplicatesRoot, d.Rel)})
	}

	items := make([]audioItem, 0, len(unique))
	artists := trackmeta.NewArtistSet()
	for _, a := range opts.KnownArtists {
		artists.AddTrusted(a)
	}
	for _, f := range unique {
		if err := ctx.Err(); err != nil {
			return domain.Plan{}, err
		}
		tags, err := p.reader.ReadMeta(f.Path)
		if err != nil {
			return domain.Plan{}, fmt.Errorf("read tags of %s: %w", f.Rel, err)
		}
		items = append(items, audioItem{entry: f, tags: tags})
		artists.AddTrusted(tags.Artist)
		artists.AddTrusted(tags.AlbumArtist)
		hints := trackmeta.HintsFromPath(f.Rel)
		artists.AddTrusted(hints.Artist)
		artists.AddCandidate(trackmeta.ParseFilename(filepath.Base(f.Rel), hints.Artist, nil).Artist)
	}

	taken := map[string]bool{}
	dirs := map[string]string{} // lower(parent/name) -> chosen folder name
	destDirBySourceDir := map[string]map[string]int{}
	for _, it := range items {
		meta, src := trackmeta.Resolve(it.tags, it.entry.Rel, artists)
		artistSeg, albumSeg, file := trackmeta.LibrarySegments(meta, filepath.Ext(it.entry.Path))
		dir := filepath.Join(l.LibraryRoot, p.folder(l.LibraryRoot, artistSeg, dirs))
		if albumSeg != "" {
			dir = filepath.Join(dir, p.folder(dir, albumSeg, dirs))
		}
		dst := p.uniquePath(filepath.Join(dir, file), taken)
		plan.Moves = append(plan.Moves, domain.Move{Kind: domain.KindAudio, From: it.entry.Path, To: dst, Source: src, Meta: meta})

		srcDir := filepath.Dir(it.entry.Path)
		if destDirBySourceDir[srcDir] == nil {
			destDirBySourceDir[srcDir] = map[string]int{}
		}
		destDirBySourceDir[srcDir][filepath.Dir(dst)]++
	}
	// Directories of duplicates still count as "audio directories" for artwork.
	for _, d := range dups {
		if _, ok := destDirBySourceDir[filepath.Dir(d.Path)]; !ok {
			destDirBySourceDir[filepath.Dir(d.Path)] = map[string]int{}
		}
	}

	for _, f := range others {
		plan.Moves = append(plan.Moves, p.planOther(f, l, destDirBySourceDir, taken))
	}

	sort.SliceStable(plan.Moves, func(i, j int) bool { return plan.Moves[i].From < plan.Moves[j].From })
	return plan, nil
}

// splitDuplicates finds byte-identical audio files. Only files sharing a size
// are hashed. The copy with the shortest relative path is kept.
func (p *Planner) splitDuplicates(ctx context.Context, files []FileEntry, known func(string) bool) (unique, dups []FileEntry, err error) {
	bySize := map[int64][]FileEntry{}
	for _, f := range files {
		bySize[f.Size] = append(bySize[f.Size], f)
	}
	for _, group := range bySize {
		if len(group) == 1 && known == nil {
			unique = append(unique, group[0])
			continue
		}
		byHash := map[string][]FileEntry{}
		for _, f := range group {
			if err := ctx.Err(); err != nil {
				return nil, nil, err
			}
			h, err := p.fs.Hash(f.Path)
			if err != nil {
				return nil, nil, fmt.Errorf("hash %s: %w", f.Rel, err)
			}
			byHash[h] = append(byHash[h], f)
		}
		for h, same := range byHash {
			if known != nil && known(h) {
				// Already in the library: every copy is a duplicate.
				dups = append(dups, same...)
				continue
			}
			sort.Slice(same, func(i, j int) bool {
				if len(same[i].Rel) != len(same[j].Rel) {
					return len(same[i].Rel) < len(same[j].Rel)
				}
				return same[i].Rel < same[j].Rel
			})
			unique = append(unique, same[0])
			dups = append(dups, same[1:]...)
		}
	}
	sort.Slice(unique, func(i, j int) bool { return unique[i].Rel < unique[j].Rel })
	return unique, dups, nil
}

// planOther places artwork next to the album it belongs to and everything
// else under the misc root, preserving the original relative path.
func (p *Planner) planOther(f FileEntry, l domain.Layout, destDirs map[string]map[string]int, taken map[string]bool) domain.Move {
	if domain.IsImage(f.Path) {
		for dir := filepath.Dir(f.Path); strings.HasPrefix(dir, l.SourceRoot) && dir != l.SourceRoot; dir = filepath.Dir(dir) {
			counts, ok := destDirs[dir]
			if !ok {
				continue
			}
			if album := majority(counts); album != "" {
				rel, _ := filepath.Rel(dir, f.Path)
				dst := p.uniquePath(filepath.Join(album, "Artwork", rel), taken)
				return domain.Move{Kind: domain.KindArtwork, From: f.Path, To: dst}
			}
			break
		}
	}
	return domain.Move{Kind: domain.KindMisc, From: f.Path, To: p.uniquePath(filepath.Join(l.MiscRoot, f.Rel), taken)}
}

// folder returns the folder name to use for name under parent: an existing
// folder that differs only in case, one chosen earlier in this plan, or
// name itself.
func (p *Planner) folder(parent, name string, chosen map[string]string) string {
	key := strings.ToLower(filepath.Join(parent, name))
	if c, ok := chosen[key]; ok {
		return c
	}
	if existing, ok := p.fs.ExistingChild(parent, name); ok {
		name = existing
	}
	chosen[key] = name
	return name
}

func majority(counts map[string]int) string {
	best, bestN := "", 0
	for k, n := range counts {
		if n > bestN || (n == bestN && k < best) {
			best, bestN = k, n
		}
	}
	return best
}

// uniquePath appends " (2)", " (3)"… until the path is free both in the plan
// and on disk.
func (p *Planner) uniquePath(path string, taken map[string]bool) string {
	ext := filepath.Ext(path)
	base := strings.TrimSuffix(path, ext)
	candidate := path
	for i := 2; taken[strings.ToLower(candidate)] || p.fs.Exists(candidate); i++ {
		candidate = fmt.Sprintf("%s (%d)%s", base, i, ext)
	}
	taken[strings.ToLower(candidate)] = true
	return candidate
}
