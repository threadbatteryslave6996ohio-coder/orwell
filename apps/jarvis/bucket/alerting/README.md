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

Environment variables control the listening host and port, alert cooldowns, alert delivery, and SMTP settings:

| Variable | Default | Purpose |
|---|---|---|
| `ALERT_SERVER_HOST` | `0.0.0.0` | Alert server bind address |
| `ALERT_SERVER_PORT` | `8082` | Alert server port |
| `ALERT_COOLDOWN_SECONDS` | `300` | Minimum seconds between alerts per source |
| `DETECTION_SERVER_HOST` | `0.0.0.0` | Detection server bind address |
| `DETECTION_SERVER_PORT` | `8083` | Detection server port |
| `DETECTION_MIN_CONFIDENCE` | `0.5` | Minimum confidence for person detection |
| `SMTP_HOST` | — | SMTP server for alert emails |
| `SMTP_PORT` | `587` | SMTP port |
| `SMTP_USERNAME` | — | SMTP username |
| `SMTP_PASSWORD` | — | SMTP password |
| `ALERT_EMAIL_TO` | — | Recipient for alert emails |

## Build

```bash
mvn test
mvn package
```

The deployment script installs the alert jar at `/opt/streaming/publish/bucket-alerting.jar` and the detection jar at `/opt/streaming/publish/bucket-detection.jar`.
