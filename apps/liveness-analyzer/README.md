# Liveness Analyzer

Watches Loki for client **heartbeat** log lines and raises a cooldown-gated alert when a client
that should be running stops beating. It is a dead-man's switch: the alert comes from the
*absence* of heartbeats, not from any error a client logged.

It is the liveness counterpart to [`log-analyzer`](../log-analyzer/README.md) (which does AI error
triage). Both read the same Loki through the same Grafana datasource proxy.

## How it works

1. The Klippy desktop clients (macOS and Linux, via the shared `DesktopClientRunner`) post
   `POST /heartbeat` to the Klippy server every `CLIENT_HEARTBEAT_INTERVAL_MS` (default 5000 ms),
   authenticated with the client's bearer token.
2. The server logs `Client heartbeat.` with the `clientId` in the metadata; the shared
   `LokiLogger` ships that line to Loki (`stream_type="app"`, `app="klippy-server"`).
3. This analyzer polls Loki on a schedule, reduces the window to the newest heartbeat per client,
   and flags any client whose last beat is older than `LIVENESS_THRESHOLD_SECONDS` (or that has
   never been seen).
4. Each down client raises one alert to `ALERT_URL`, gated by `LIVENESS_ALERT_COOLDOWN_SECONDS` so
   a client that stays down does not alert every check. When a client recovers, its cooldown is
   cleared so the next outage alerts immediately.

Because a client that never starts produces no log line, set `LIVENESS_EXPECTED_CLIENTS` to the
client ids that must be alive — those are checked even when Loki has never seen them. Clients not
in that list are still monitored once they have been seen at least once within the lookback window.

> **Heartbeats do not label per client.** `clientId` stays in the log *body* (`| json` reaches it
> at query time), never a Loki label — a per-client label would create one Loki stream per user.
> This matches the cardinality rule in [`log-analyzer/README.md`](../log-analyzer/README.md).

## Configuration

Like `log-analyzer`, the three Grafana keys are `optional` with defaults, so the app **starts
happily misconfigured** and will report every client as down forever if they point at a Loki
nothing writes to. Treat them as required in practice, and make sure `GRAFANA_LOKI_DATASOURCE_UID`
points at the same Loki the servers push to via `LOKI_URL`.

| Variable | Default | Purpose |
|---|---|---|
| `LIVENESS_CHECK_INTERVAL_SECONDS` | `5` | How often to poll Loki. |
| `LIVENESS_LOOKBACK_SECONDS` | `60` | Query window per poll. |
| `LIVENESS_THRESHOLD_SECONDS` | `15` | Silence beyond this marks a client down (≈3 missed 5 s beats). |
| `LIVENESS_MAX_LOG_LINES` | `500` | Cap on heartbeat lines fetched per poll. |
| `LIVENESS_EXPECTED_CLIENTS` | `` | Comma-separated client ids that must be alive. |
| `LIVENESS_LOKI_QUERY` | `{stream_type="app"} \| json \| message="Client heartbeat."` | Selector for heartbeat lines. |
| `LIVENESS_ALERT_COOLDOWN_SECONDS` | `300` | Minimum gap between repeat alerts for the same client. |
| `GRAFANA_URL` | `http://127.0.0.1:3000` | Grafana base URL. |
| `GRAFANA_API_TOKEN` | `` | Bearer token for the Grafana proxy. |
| `GRAFANA_LOKI_DATASOURCE_UID` | `` | UID of the Loki datasource in Grafana. |
| `ALERT_URL` | `http://127.0.0.1:9000/alerts` | Alert service endpoint. |

Common `AppServerEnv` keys (`SERVER_ADDRESS`, `SERVER_PORT`, `LOKI_URL`, …) apply as well.

## Endpoints

- `POST /run-once` — run one liveness check immediately, off the schedule (returns `409` if a
  check is already in progress). Useful for probing and tests.
- `GET /health` — shared health endpoint; its details carry per-client `status`/`lastSeen`, plus
  `checksTotal`, `alertsSentTotal`, `alertsRejectedTotal`, `errorsTotal`, and `lastError`.

## Run

```bash
mvn -pl :liveness-analyzer -am package
java -jar apps/liveness-analyzer/target/liveness-analyzer-0.1.0-SNAPSHOT-exec.jar
```

## Test

```bash
mvn -pl :liveness-analyzer -am test
```
