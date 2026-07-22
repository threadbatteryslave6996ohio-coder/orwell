# Alerting

Standalone alert-delivery service. It receives alert events over HTTP and
forwards them via email (SMTP).

## HTTP server

The executable can use Spring Boot/Tomcat or the lighter embedded Undertow
engine. Both expose `POST /alerts` and `GET /health` and share `AlertService`.
The Undertow adapter limits alert bodies to 1 MiB and returns a JSON `413`
response with `request body too large` when that limit is exceeded.

```bash
SERVER_ENGINE=undertow java -jar target/alerting-0.1.0-SNAPSHOT-exec.jar
SERVER_ENGINE=spring java -jar target/alerting-0.1.0-SNAPSHOT-exec.jar
```

## Configuration

Environment variables control the listening host and port, alert delivery, and
SMTP settings (see `.env.example`):

| Variable | Default / `.env.example` value | Purpose |
|---|---|---|
| `SERVER_ADDRESS` | **required** — `.env.example` uses `127.0.0.1` | Alert server bind address |
| `SERVER_PORT` | **required** — `.env.example` uses `9000` | Alert server port |
| `SERVER_ENGINE` | `spring` (see note) | HTTP engine: `spring` or `undertow` |
| `ALERT_EMAIL_ENABLED` | `false` | Enable email delivery |
| `ALERT_EMAIL_TO` | — | Recipient for alert emails |
| `ALERT_EMAIL_FROM` | — | Sender address (defaults to `ALERT_EMAIL_TO`) |
| `SMTP_HOST` | — | SMTP server for alert emails |
| `SMTP_PORT` | `587` | SMTP port |
| `SMTP_USERNAME` | — | SMTP username |
| `SMTP_PASSWORD` | — | SMTP password |
| `SMTP_USE_TLS` | `true` | Use STARTTLS |
| `ALERT_LOG_FILE` | `/var/log/streaming/alerts.log` | Alert log file path |

`SERVER_ADDRESS` and `SERVER_PORT` are registered as `required` by `AppServerEnv`: unset, the
app exits at startup with a validation error rather than falling back to the values above.

`SERVER_ENGINE` is **not** part of `AlertEnvs` and is not validated by the env schema —
`ServerRuntime` reads it straight from the process environment before the schema runs, so don't
go looking for it there.

## Alert log format

`ALERT_LOG_FILE` receives one JSON object per line from the shared
`dev.orwell.logging.JsonLogger`: `timestamp`, `level`, `message`, then the entry's metadata
flattened alongside them. Metadata used to be nested under a `fields` key — consumers that read
`fields.*` need to read the top-level keys instead.

## Build

```bash
mvn -pl apps/alerting -am package
```

The deployment script installs the alert jar at `/opt/streaming/publish/alerting.jar`.
