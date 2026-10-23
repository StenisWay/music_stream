package application

import (
	"context"
	"errors"
	"musicstream/internal/feature/auth/domain"
	"musicstream/internal/shared"
	"strings"
	"testing"
	"time"
)

// ---- fakes ----

type memUsers struct {
	users  map[string]domain.User
	tokens map[string]domain.RefreshToken
}

func newMemUsers() *memUsers {
	return &memUsers{users: map[string]domain.User{}, tokens: map[string]domain.RefreshToken{}}
}

func (m *memUsers) GetByUsername(_ context.Context, n string) (domain.User, error) {
	u, ok := m.users[n]
	if !ok {
		return domain.User{}, shared.ErrNotFound
	}
	return u, nil
}

func (m *memUsers) Get(_ context.Context, id shared.UserID) (domain.User, error) {
	for _, u := range m.users {
		if u.ID == id {
			return u, nil
		}
	}
	return domain.User{}, shared.ErrNotFound
}

func (m *memUsers) Upsert(_ context.Context, u domain.User) (domain.User, error) {
	if u.ID == 0 {
		u.ID = shared.UserID(len(m.users) + 1)
	}
	m.users[u.Username] = u
	return u, nil
}

func (m *memUsers) SaveRefreshToken(_ context.Context, t domain.RefreshToken) error {
	m.tokens[t.ID] = t
	return nil
}

func (m *memUsers) GetRefreshToken(_ context.Context, id string) (domain.RefreshToken, error) {
	t, ok := m.tokens[id]
	if !ok {
		return domain.RefreshToken{}, shared.ErrNotFound
	}
	return t, nil
}

func (m *memUsers) RevokeRefreshToken(_ context.Context, id string, at time.Time) error {
	t, ok := m.tokens[id]
	if !ok || t.RevokedAt != nil {
		return shared.ErrNotFound
	}
	t.RevokedAt = &at
	m.tokens[id] = t
	return nil
}

func (m *memUsers) RevokeAllRefreshTokens(_ context.Context, user shared.UserID, at time.Time) error {
	for id, t := range m.tokens {
		if t.UserID == user && t.RevokedAt == nil {
			t.RevokedAt = &at
			m.tokens[id] = t
		}
	}
	return nil
}

type plainHasher struct{}

func (plainHasher) Hash(p string) (string, error) { return "h:" + p, nil }
func (plainHasher) Compare(h, p string) error {
	if h != "h:"+p {
		return errors.New("mismatch")
	}
	return nil
}

type fakeIssuer struct{}

func (fakeIssuer) Issue(u shared.UserID, ttl time.Duration) (string, time.Time, error) {
	return "access-" + string(rune('0'+u)), time.Now().Add(ttl), nil
}

func (fakeIssuer) Verify(tok string) (shared.UserID, error) {
	if !strings.HasPrefix(tok, "access-") {
		return 0, errors.New("bad")
	}
	return shared.UserID(tok[len(tok)-1] - '0'), nil
}

func newTestService(t *testing.T) (*Service, *memUsers) {
	t.Helper()
	users := newMemUsers()
	s := NewService(users, plainHasher{}, fakeIssuer{}, Config{AccessTTL: time.Hour, RefreshTTL: time.Hour})
	if err := s.EnsureUser(context.Background(), "admin", "h:secret"); err != nil {
		t.Fatal(err)
	}
	return s, users
}

// ---- tests ----

func TestLogin(t *testing.T) {
	s, _ := newTestService(t)
	ctx := context.Background()

	if _, err := s.Login(ctx, "admin", "wrong", "phone"); !errors.Is(err, shared.ErrUnauthenticated) {
		t.Fatalf("wrong password: got %v", err)
	}
	if _, err := s.Login(ctx, "nobody", "secret", "phone"); !errors.Is(err, shared.ErrUnauthenticated) {
		t.Fatalf("unknown user: got %v", err)
	}
	pair, err := s.Login(ctx, "admin", "secret", "phone")
	if err != nil {
		t.Fatal(err)
	}
	if uid, err := s.Authenticate(pair.AccessToken); err != nil || uid != 1 {
		t.Fatalf("authenticate: uid=%d err=%v", uid, err)
	}
}

func TestRefreshRotatesAndDetectsReplay(t *testing.T) {
	s, _ := newTestService(t)
	ctx := context.Background()
	first, err := s.Login(ctx, "admin", "secret", "phone")
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.Refresh(ctx, first.RefreshToken)
	if err != nil {
		t.Fatalf("refresh: %v", err)
	}
	if second.RefreshToken == first.RefreshToken {
		t.Fatal("refresh token was not rotated")
	}
	// Replaying the old token must fail and revoke the new one too.
	if _, err := s.Refresh(ctx, first.RefreshToken); !errors.Is(err, shared.ErrUnauthenticated) {
		t.Fatalf("replay: got %v", err)
	}
	if _, err := s.Refresh(ctx, second.RefreshToken); !errors.Is(err, shared.ErrUnauthenticated) {
		t.Fatalf("token family should be revoked after replay, got %v", err)
	}
}

func TestRefreshRejectsTamperedSecret(t *testing.T) {
	s, _ := newTestService(t)
	ctx := context.Background()
	pair, _ := s.Login(ctx, "admin", "secret", "phone")
	id, _, _ := strings.Cut(pair.RefreshToken, ".")
	if _, err := s.Refresh(ctx, id+".forged"); !errors.Is(err, shared.ErrUnauthenticated) {
		t.Fatalf("got %v", err)
	}
	if _, err := s.Refresh(ctx, "garbage"); !errors.Is(err, shared.ErrUnauthenticated) {
		t.Fatalf("got %v", err)
	}
}

func TestPasswordChangeRevokesSessions(t *testing.T) {
	s, _ := newTestService(t)
	ctx := context.Background()
	pair, _ := s.Login(ctx, "admin", "secret", "phone")
	if err := s.EnsureUser(ctx, "admin", "h:newsecret"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Refresh(ctx, pair.RefreshToken); !errors.Is(err, shared.ErrUnauthenticated) {
		t.Fatalf("got %v", err)
	}
}
