# Liveness Analyzer

Watches Loki for client **heartbeat** log lines and raises a cooldown-gated alert when a client
that should be running stops beating. It is a dead-man's switch: the alert comes from the
*absence* of heartbeats, not from any error a client logged.

It is the liveness counterpart to [`log-analyzer`](../log-analyzer/README.md) (which does AI error
triage). Both read the same Loki through the same Grafana datasource proxy.

**It is not [`health-monitor`](../health-monitor/README.md), and neither subsumes the other.** This app
infers liveness from the *absence* of heartbeat log lines, which is the only option for something
nothing can reach to poll — a desktop client behind a home router. That app actively probes `/health`
over HTTP, which catches a server that is up but broken, and which no amount of watching logs would
reveal. A new "is X alive" check belongs in whichever of the two matches how X can be observed.

**The two can cover each other**, and this is the one case where they should. `health-monitor`'s own
failure announces nothing — silence is its normal output — so it publishes a heartbeat to
`stream_type="heartbeat"` with `clientId=health-monitor` for this app to alert on the absence of:

```bash
LIVENESS_LOKI_QUERY='{stream_type="heartbeat"} | json | message="Health monitor heartbeat."'
LIVENESS_EXPECTED_CLIENTS=health-monitor
LIVENESS_THRESHOLD_SECONDS=180
```

Note that is a *different* query from the default, so watching the monitor and watching the klippy
clients means two deployments of this app rather than one — `LIVENESS_LOKI_QUERY` is one selector.

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

### The fuse: an empty Loki looks exactly like everything dying

A heartbeat source that answers with nothing is indistinguishable from every client going silent in
the same second, and the ways to get an empty answer are mundane: Loki restarted, its retention is
shorter than `LIVENESS_LOOKBACK_SECONDS`, a label changed, or this analyzer is pointed at a different
Loki than the one clients push to. Without a guard, any of those sends one alert per client at once.

So a check that finds at least `LIVENESS_MAX_DOWN_PERCENT` of clients down is **not believed**: no
alerts are dispatched, `/health` reports `alertsSuppressed` with the reason, and the run response
carries the same. The status map still shows every client as down — only the fan-out is held, so an
operator sees more, not less.

The default is `100`, so this only ever withholds on a total wipeout; **any partial outage alerts
exactly as before**. It never applies below three clients, where "all of them" is an ordinary outage
rather than a statement about the source. Set `0` to disable it, or a lower percentage to be
stricter. This is the same reasoning as insta's `INSTA_MAX_RETIRE_PERCENT`, which refuses to believe
a follower walk that would retire implausibly many accounts at once.

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
| `LIVENESS_MAX_DOWN_PERCENT` | `100` | A check finding at least this share of clients down is refused rather than alerted on. `0` disables the fuse. |
| `GRAFANA_URL` | `http://127.0.0.1:3000` | Grafana base URL. |
| `GRAFANA_API_TOKEN` | `` | Bearer token for the Grafana proxy. |
| `GRAFANA_LOKI_DATASOURCE_UID` | `` | UID of the Loki datasource in Grafana. |
| `ALERT_URL` | `http://127.0.0.1:9000/alerts` | Alert service endpoint. |

Common `AppServerEnv` keys (`SERVER_ADDRESS`, `SERVER_PORT`, `LOGGER`, `LOKI_URL`, …) apply as
well — `LOGGER` chooses this service's own sinks (`console`, `disk`, `loki`, `loki-with-fallback`,
`both`) and has nothing to do with the Loki it queries.

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
