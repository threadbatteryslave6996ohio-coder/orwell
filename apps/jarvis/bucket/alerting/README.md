# Alerting Services

This module contains the Java detection and alert services used by the stream pipeline.

## Flow

```text
MediaMTX -> analyze_stream.sh -> detection service -> alert service -> email
```

## Services

- `DetectorApplication`: starts the detection HTTP server
- `DetectionServer`: handles `POST /detect`
- `HogPersonDetector`: lightweight Java person-detection implementation
- `AlertServerMain`: starts the alert HTTP server
- `AlertServer`: handles `POST /alerts`

## Configuration

Environment variables control the listening host and port, alert cooldowns, alert delivery, and SMTP settings. See the Java source for the exact names used by each service.

## Build

```bash
mvn test
mvn package
```

The deployment script installs the alert jar at `/opt/streaming/publish/bucket-alerting.jar` and the detection jar at `/opt/streaming/publish/bucket-detection.jar`.
