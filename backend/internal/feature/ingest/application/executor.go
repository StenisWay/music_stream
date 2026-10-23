package application

import (
	"context"
	"errors"
	"fmt"
	"musicstream/internal/feature/ingest/domain"
)

// Executor applies a domain.Plan, logging every successful move so the whole run
// can be reverted with Undo.
type Executor struct {
	fs FileSystem
}

func NewExecutor(fs FileSystem) *Executor { return &Executor{fs: fs} }

// Result summarises an Apply run.
type Result struct {
	Moved       int
	RemovedDirs int
	Failed      []error
}

// Apply executes the plan. It keeps going after individual failures and
// reports them, because a half-applied plan is still fully undoable.
func (e *Executor) Apply(ctx context.Context, plan domain.Plan, log MoveLog, cleanupRoot string) (Result, error) {
	var res Result
	for _, m := range plan.Moves {
		if err := ctx.Err(); err != nil {
			return res, err
		}
		if e.fs.Exists(m.To) {
			res.Failed = append(res.Failed, fmt.Errorf("skip %s: destination exists", m.From))
			continue
		}
		if err := e.fs.Move(m.From, m.To); err != nil {
			res.Failed = append(res.Failed, fmt.Errorf("move %s: %w", m.From, err))
			continue
		}
		if err := log.Append(m); err != nil {
			// Without a log entry the move could not be undone: stop here.
			return res, fmt.Errorf("write undo log: %w", err)
		}
		res.Moved++
	}
	if cleanupRoot != "" {
		n, err := e.fs.RemoveEmptyDirs(cleanupRoot)
		res.RemovedDirs = n
		if err != nil {
			res.Failed = append(res.Failed, fmt.Errorf("remove empty dirs: %w", err))
		}
	}
	return res, nil
}

// Undo reverts previously logged moves in reverse order.
func (e *Executor) Undo(ctx context.Context, moves []domain.Move) (Result, error) {
	var res Result
	for i := len(moves) - 1; i >= 0; i-- {
		if err := ctx.Err(); err != nil {
			return res, err
		}
		m := moves[i]
		if e.fs.Exists(m.From) {
			res.Failed = append(res.Failed, fmt.Errorf("skip %s: original location occupied", m.To))
			continue
		}
		if err := e.fs.Move(m.To, m.From); err != nil {
			res.Failed = append(res.Failed, fmt.Errorf("restore %s: %w", m.To, err))
			continue
		}
		res.Moved++
	}
	if len(res.Failed) > 0 {
		return res, errors.Join(res.Failed...)
	}
	return res, nil
}
