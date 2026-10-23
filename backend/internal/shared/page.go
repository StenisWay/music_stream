package shared

// Page is an offset/limit window.
type Page struct {
	Limit  int
	Offset int
}

const (
	DefaultPageSize = 50
	MaxPageSize     = 500
)

// Normalize clamps the page to sane bounds.
func (p Page) Normalize() Page {
	if p.Limit <= 0 {
		p.Limit = DefaultPageSize
	}
	if p.Limit > MaxPageSize {
		p.Limit = MaxPageSize
	}
	if p.Offset < 0 {
		p.Offset = 0
	}
	return p
}

// PageResult is a window of items plus the total number of matches.
type PageResult[T any] struct {
	Items []T
	Total int
	Page  Page
}
