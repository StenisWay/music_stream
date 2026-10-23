# Build: pure-Go binary (modernc SQLite, no cgo).
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=docker
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/ ./cmd/...

# Runtime: ffmpeg enables WMA -> MP3 transcoding; yt-dlp (with deno for
# YouTube's player JS) powers searching and downloading online songs.
FROM alpine:3.22
# Optional faster package mirror, e.g. https://mirror.twds.com.tw/alpine
ARG ALPINE_MIRROR=
RUN if [ -n "$ALPINE_MIRROR" ]; then sed -i "s#https://dl-cdn.alpinelinux.org/alpine#$ALPINE_MIRROR#" /etc/apk/repositories; fi \
 && apk add --no-cache ffmpeg ca-certificates tzdata python3 py3-pip deno
# yt-dlp from PyPI, not apk: sites change often and only recent releases keep
# working. Rebuild the image (docker compose build --pull) to update it.
RUN pip install --no-cache-dir --break-system-packages --root-user-action=ignore "yt-dlp[default]"
COPY --from=build /out/musicserver /out/musicorg /usr/local/bin/
COPY deploy/docker/config.yaml /etc/musicstream/config.yaml
ENTRYPOINT ["musicserver"]
CMD ["serve", "-config", "/etc/musicstream/config.yaml"]
