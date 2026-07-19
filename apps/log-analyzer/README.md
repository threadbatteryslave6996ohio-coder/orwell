# Log Analyzer

Polls logs through Grafana's datasource proxy, runs recent error logs through an AI model, and forwards important findings to the alert service.

Configure `GRAFANA_URL`, `GRAFANA_API_TOKEN`, and `GRAFANA_LOKI_DATASOURCE_UID` to point at a Grafana instance with a Loki datasource.

## What it queries

`LOKI_QUERY` defaults to:

```logql
{stream_type="app"} | json | level="ERROR"
```

`stream_type="app"` is set by the shared `dev.orwell.logging` sink on everything it pushes, so
this selector matches app logs and nothing else. Infra and framework logs are not shipped by this
repo at all — they are prose, and re-parsing them would waste AI tokens on lines carrying no
fields.

Note that a Loki stream selector cannot be empty — `{}` is rejected — so the selector must name at
least one label. An earlier default did a case-insensitive regex scan for
`error|exception|fatal|panic` over raw text; that both missed failures worded differently and
matched INFO lines that merely contained the word, which is exactly what the structured `level`
field now settles.

## How the app logs get to Loki

Each server pushes its own logs directly. The shared `Logger` bean
(`packages/server-bootstrap`, `LoggerConfiguration`) fans out to a console sink and a
`LokiLogger` that batches entries onto a bounded queue and ships them to `LOKI_URL` from a
background thread. There is no collector and no log file — see
[`packages/logger/README.md`](../../packages/logger/README.md).

**`LOKI_URL` and `GRAFANA_LOKI_DATASOURCE_UID` must point at the same Loki.** If they diverge,
this analyzer queries an instance nothing writes to and reports zero errors forever. Nothing
checks this for you, and the failure is indistinguishable from a healthy stack.

### Labels

Three, and every one is bounded:

| Label | Source | Values |
|---|---|---|
| `stream_type="app"` | set by `LokiLogger` | 1 — the label `LOKI_QUERY` selects on |
| `app` | `orwell.app.name` | one per deployed service |
| `level` | the entry's `LogLevel` | 5 (`TRACE`…`ERROR`) |

The rule: **a field may become a label only if its value set is bounded by the number of deployed
services, not by traffic.** `clientId`, `entryId`, and `endpoint` stay in the line body, where
`| json` reaches them at query time. Every distinct label value creates a Loki stream and an index
entry, so a per-client label would create one stream per user, forever.

### The `app` label is not always the compose service name

It is `orwell.app.name` — the identity the service chose for itself and the string
`ConsoleLogger` prints. Two of the seven diverge, so query accordingly:

| Compose service | `app` label |
|---|---|
| `auth-server` | `auth-server` |
| `clipboard-server` | **`klippy-server`** |
| `secrets-manager` | `secrets-manager` |
| `alerting` | `alerting` |
| `log-analyzer` | `log-analyzer` |
| `keeboarder-server` | `keeboarder-server` |
| `jarvis-proxy` | **`bucket-proxy`** |

### What direct push costs

The send buffer is in JVM memory. Entries queued but not yet shipped are lost if a process dies,
and entries are dropped once the bounded queue fills — a slow or unreachable Loki is never
allowed to slow a request path instead. `LokiLogger` counts drops and reports them to stderr
periodically.

If `LOKI_URL` is unreachable, every service stays healthy and this analyzer sees zero errors,
which looks exactly like a clean stack. Nothing alerts on that. Tracked in `CODE_REFACTOR.md`.

## Run

```bash
mvn -pl apps/log-analyzer -am package
SERVER_ENGINE=undertow java -jar apps/log-analyzer/target/log-analyzer-0.1.0-SNAPSHOT-exec.jar
```

Set `SERVER_ENGINE` to `undertow` for the lightweight runtime or `spring` for
the existing Spring Boot/Tomcat runtime. Both engines expose the same
`GET /health` and `POST /run-once` endpoints and share the analyzer service.

See [README.docker.md](./README.docker.md) for the compose-based setup.

## Example env

See [`./.env.example`](./.env.example).
