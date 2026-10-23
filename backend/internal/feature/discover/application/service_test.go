package application_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"musicstream/internal/feature/discover/application"
	"musicstream/internal/feature/discover/domain"
	"musicstream/internal/shared"
)

type fakeFetcher struct {
	available bool
	fail      error
	release   chan struct{} // when set, Download waits for it
	cleaned   int
	mu        sync.Mutex
}

func (f *fakeFetcher) Available() bool { return f.available }

func (f *fakeFetcher) Search(_ context.Context, src domain.Source, q string, limit int) ([]domain.Candidate, error) {
	return []domain.Candidate{{Source: src, URL: "https://youtu.be/x", Title: q, Uploader: "channel"}}, nil
}

func (f *fakeFetcher) Download(ctx context.Context, url string, _ domain.TrackInfo) (string, func(), error) {
	if f.release != nil {
		select {
		case <-f.release:
		case <-ctx.Done():
			return "", nil, ctx.Err()
		}
	}
	cleanup := func() { f.mu.Lock(); f.cleaned++; f.mu.Unlock() }
	if f.fail != nil {
		return "", cleanup, f.fail
	}
	return "/work/" + url[len(url)-1:] + ".mp3", cleanup, nil
}

type artists []string

func (a artists) KnownArtists(context.Context) ([]string, error) { return a, nil }

type fakeInbox struct {
	mu        sync.Mutex
	delivered map[string]string // name -> file
}

func (i *fakeInbox) Deliver(file, name string) error {
	i.mu.Lock()
	defer i.mu.Unlock()
	i.delivered[name] = file
	return nil
}

func newService(f *fakeFetcher, inbox application.Inbox) *application.Service {
	return application.NewService(f, inbox, artists{"周杰倫"}, application.Config{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

// waitFor polls the job list until cond holds for job id.
func waitFor(t *testing.T, s *application.Service, id domain.JobID, status domain.Status) domain.Job {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		for _, j := range s.Jobs() {
			if j.ID == id && j.Status == status {
				return j
			}
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("job %d never reached %s: %+v", id, status, s.Jobs())
	return domain.Job{}
}

func TestDownloadIntoInbox(t *testing.T) {
	f := &fakeFetcher{available: true, release: make(chan struct{})}
	inbox := &fakeInbox{delivered: map[string]string{}}
	s := newService(f, inbox)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go s.Run(ctx)

	j, err := s.Enqueue("https://www.youtube.com/watch?v=a", domain.TrackInfo{Artist: " 周杰倫 ", Title: "晴天"})
	if err != nil {
		t.Fatal(err)
	}
	waitFor(t, s, j.ID, domain.StatusDownloading)

	// Asking again while it runs returns the same job.
	again, err := s.Enqueue("https://www.youtube.com/watch?v=a", domain.TrackInfo{Title: "other"})
	if err != nil || again.ID != j.ID {
		t.Fatalf("duplicate enqueue: %+v, %v", again, err)
	}

	close(f.release)
	waitFor(t, s, j.ID, domain.StatusDone)
	if got := inbox.delivered["周杰倫 - 晴天.mp3"]; got != "/work/a.mp3" {
		t.Fatalf("delivered: %v", inbox.delivered)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.cleaned != 1 {
		t.Fatalf("work folder should be cleaned up once, got %d", f.cleaned)
	}
}

func TestDownloadFailure(t *testing.T) {
	f := &fakeFetcher{available: true, fail: errors.New("yt-dlp: exit 1\nERROR: [youtube] a: Video unavailable")}
	s := newService(f, &fakeInbox{delivered: map[string]string{}})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go s.Run(ctx)

	j, err := s.Enqueue("https://youtu.be/a", domain.TrackInfo{Title: "x"})
	if err != nil {
		t.Fatal(err)
	}
	got := waitFor(t, s, j.ID, domain.StatusFailed)
	if got.Error != "ERROR: [youtube] a: Video unavailable" {
		t.Fatalf("error = %q", got.Error)
	}
}

func TestSearchGuessesWithLibraryArtists(t *testing.T) {
	s := newService(&fakeFetcher{available: true}, &fakeInbox{})
	res, err := s.Search(context.Background(), domain.SourceYouTube, "晴天 - 周杰倫", 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].Guess != (domain.TrackInfo{Artist: "周杰倫", Title: "晴天"}) {
		t.Fatalf("guess: %+v", res)
	}
}

func TestValidation(t *testing.T) {
	s := newService(&fakeFetcher{available: true}, &fakeInbox{})
	if _, err := s.Enqueue("https://evil.example/a", domain.TrackInfo{Title: "x"}); !errors.Is(err, shared.ErrInvalidArgument) {
		t.Errorf("foreign URL: %v", err)
	}
	if _, err := s.Enqueue("https://youtu.be/a", domain.TrackInfo{Title: " "}); !errors.Is(err, shared.ErrInvalidArgument) {
		t.Errorf("blank title: %v", err)
	}
	if _, err := s.Search(context.Background(), domain.SourceYouTube, "  ", 10); !errors.Is(err, shared.ErrInvalidArgument) {
		t.Errorf("blank query: %v", err)
	}

	for name, svc := range map[string]*application.Service{
		"no yt-dlp": newService(&fakeFetcher{}, &fakeInbox{}),
		"no inbox":  newService(&fakeFetcher{available: true}, nil),
	} {
		if _, err := svc.Search(context.Background(), domain.SourceYouTube, "q", 10); !errors.Is(err, domain.ErrUnavailable) {
			t.Errorf("%s search: %v", name, err)
		}
		if _, err := svc.Enqueue("https://youtu.be/a", domain.TrackInfo{Title: "x"}); !errors.Is(err, domain.ErrUnavailable) {
			t.Errorf("%s enqueue: %v", name, err)
		}
	}
}
