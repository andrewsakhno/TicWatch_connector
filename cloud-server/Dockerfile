# Cross-compiles on the build host (no QEMU); the result is a single static binary in a scratch image.
# Build with `--platform linux/386` for the netbook (TARGET_ARCH in the scripts); linux/amd64 needs no other change.
ARG GO_VERSION=1.27

FROM --platform=$BUILDPLATFORM golang:${GO_VERSION} AS build
ARG TARGETARCH
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd cmd
COPY internal internal
RUN CGO_ENABLED=0 GOOS=linux GOARCH=$TARGETARCH go build -trimpath -ldflags="-s -w" -o /out/cloud-server ./cmd/cloud-server

FROM scratch
COPY --from=build /out/cloud-server /cloud-server
USER 65534:65534
EXPOSE 8766
ENTRYPOINT ["/cloud-server"]
