# Alerting

Standalone alert-delivery service. It receives alert events over HTTP and
forwards them via email (SMTP). It was previously bundled under `jarvis/bucket`
and is now its own top-level app (`apps/alerting`).

## Flow

```text
detection service -> alert service -> email
```

## Services

- `AlertServerMain`: starts the alert HTTP server
- `AlertServer`: handles `POST /alerts` and `GET /health`

## Configuration

Environment variables control the listening host and port, alert delivery, and
SMTP settings (see `.env.example`):

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | Alert server bind address |
| `SERVER_PORT` | `9000` | Alert server port |
| `ALERT_EMAIL_ENABLED` | `false` | Enable email delivery |
| `ALERT_EMAIL_TO` | — | Recipient for alert emails |
| `ALERT_EMAIL_FROM` | — | Sender address (defaults to `ALERT_EMAIL_TO`) |
| `SMTP_HOST` | — | SMTP server for alert emails |
| `SMTP_PORT` | `587` | SMTP port |
| `SMTP_USERNAME` | — | SMTP username |
| `SMTP_PASSWORD` | — | SMTP password |
| `SMTP_USE_TLS` | `true` | Use STARTTLS |
| `ALERT_LOG_FILE` | `/var/log/streaming/alerts.log` | Alert log file path |

## Build

```bash
mvn -pl apps/alerting -am package
```

The deployment script installs the alert jar at `/opt/streaming/publish/alerting.jar`.
