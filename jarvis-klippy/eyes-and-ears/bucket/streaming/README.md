# Stream Ingest Fan-Out

This directory contains the Java stream worker and the shell wrappers that move video through the pipeline.

## Architecture

```text
client -> MediaMTX ingest -> local recorder -> disk
                          \-> analyze_stream.sh -> Java stream worker -> detection service
```

## Runtime

- `MediaMTX` receives RTMP input
- `record_stream.sh` records segmented MP4 files
- `analyze_stream.sh` invokes the Java stream worker
- `AnalysisWorker` sends sampled frames to the configured analysis endpoint

## Stream URL

Publish to `rtmp://SERVER_IP:1935/live`. The recorder and analyzer read from the local `rtsp://127.0.0.1:8554/live` relay.

## Storage

Recorded files are written to `/var/lib/streaming/recordings/`.

## Build

```bash
mvn test
mvn package
```

The deployment script installs the streaming jar at `/opt/streaming/publish/bucket-streaming.jar` and keeps the two helper scripts in `/opt/streaming/scripts/`.
