GO      ?= $(HOME)/sdk/go/bin/go
VERSION ?= $(shell date +%Y.%m.%d)
LDFLAGS := -s -w -X main.version=$(VERSION)

.PHONY: build test vet run organize organize-apply hash-password clean

build:
	$(GO) build -trimpath -ldflags "$(LDFLAGS)" -o bin/ ./cmd/...

test:
	$(GO) test ./...

vet:
	$(GO) vet ./...
	@test -z "$$($(HOME)/sdk/go/bin/gofmt -l .)" || (echo "gofmt needed:"; $(HOME)/sdk/go/bin/gofmt -l .; exit 1)

run: build
	./bin/musicserver serve -config config.yaml

# Dry run: writes ../music_data/.musicorg/plan-*.tsv for review.
organize: build
	./bin/musicorg -src ../music_data/inbox -dest ../music_data

organize-apply: build
	./bin/musicorg -src ../music_data/inbox -dest ../music_data -apply

hash-password: build
	./bin/musicserver hash-password

clean:
	rm -rf bin
