package infrastructure

import (
	"bufio"
	"encoding/json"
	"fmt"
	"musicstream/internal/feature/ingest/domain"
	"os"
	"path/filepath"
)

// MoveLog is an append-only JSON Lines undo log. Every entry is flushed and
// fsynced before the next move so a crash never loses an executed move.
type MoveLog struct {
	f *os.File
}

func CreateMoveLog(path string) (*MoveLog, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o644)
	if err != nil {
		return nil, err
	}
	return &MoveLog{f: f}, nil
}

func (l *MoveLog) Append(m domain.Move) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	if _, err := l.f.Write(append(b, '\n')); err != nil {
		return err
	}
	return l.f.Sync()
}

func (l *MoveLog) Close() error { return l.f.Close() }

// ReadMoveLog loads all entries of a log written by MoveLog.
func ReadMoveLog(path string) ([]domain.Move, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var moves []domain.Move
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for line := 1; sc.Scan(); line++ {
		var m domain.Move
		if err := json.Unmarshal(sc.Bytes(), &m); err != nil {
			return nil, fmt.Errorf("%s:%d: %w", path, line, err)
		}
		moves = append(moves, m)
	}
	return moves, sc.Err()
}
