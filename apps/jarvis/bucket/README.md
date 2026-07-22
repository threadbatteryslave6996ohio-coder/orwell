# Bucket object storage proxy

This subsystem is a Java upload proxy in front of an object-storage bucket. The proxy speaks the
S3 protocol against any S3-compatible store (e.g. a self-hosted [MinIO](https://min.io)) or Azure
Blob, so uploads never need cloud-vendor credentials on the clients. The Java services carry the
stream work too; the shell pieces are the stream helper scripts and the recorder clients under
`../clients/`.

## Components

- `proxy/`: Java upload proxy, which also runs the stream analysis worker as
  `--mode=stream-worker` and carries the stream helper scripts in `proxy/scripts/`

Alert delivery lives in `apps/alerting` and person detection in `apps/jarvis/detection`; both
are standalone apps deployed alongside the proxy.

## Deployment

The proxy ships as a container. See [`proxy/docker/deployment/`](proxy/docker/deployment/) for
the compose file and `.env.example`; point `PROXY_S3_ENDPOINT` at your bucket service and set the
storage credentials. For local development against MinIO, use
[`proxy/scripts/local-stack.sh`](proxy/scripts/local-stack.sh) (see
[proxy/LOCAL_TESTING.md](proxy/LOCAL_TESTING.md)).

## Proxy API

The proxy runs as a standalone HTTP service on port `5000`. Reverse proxy and
TLS termination are intentionally left outside this repo.
See [proxy/README.md](proxy/README.md) for the endpoint list and local run instructions.

## Stream Flow

MediaMTX receives RTMP input, the recorder writes segmented MP4 files, and the analyzer forwards sampled frames to the Java detection and alert services.

## Documentation

- [proxy/README.md](proxy/README.md)
- [proxy/LOCAL_TESTING.md](proxy/LOCAL_TESTING.md)
- [../../alerting/README.md](../../alerting/README.md)
