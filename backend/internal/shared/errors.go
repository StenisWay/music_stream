// Package shared is the shared kernel: the few domain concepts every
// feature agrees on (error categories, paging, user identity). It must stay
// tiny and must not import any other package of this module.
package shared

import "errors"

// Sentinel errors. Adapters translate them to transport-specific codes
// (HTTP status, etc.); wrap them with fmt.Errorf("...: %w", ErrX) to add context.
var (
	ErrNotFound        = errors.New("not found")
	ErrInvalidArgument = errors.New("invalid argument")
	ErrConflict        = errors.New("conflict")
	ErrUnauthenticated = errors.New("unauthenticated")
	ErrUnsupported     = errors.New("unsupported")
)
