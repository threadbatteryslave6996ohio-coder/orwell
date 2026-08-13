# orwell

Multi-module monorepo for a suite of backend services and desktop clients.

## Apps

| App | Directory | Purpose |
|---|---|---|
| Alerting | `apps/alerting` | Email alert dispatch with per-source cooldowns |
| Analyzer | `apps/analyzer` | Analysis service |
| Auth | `apps/auth` | Client identity and token management |
| Backup | `apps/backup` | Postgres backup runner |
| Google | `apps/google` | Gmail integration service |
| Insta | `apps/insta` | CLI for public Instagram follower/following lookups via the Apify marketplace |
| Jarvis | `apps/jarvis` | Surveillance: frame hub (SSE), person detection, retention worker, recorder clients |
| Keeboarder | `apps/keeboarder` | Keyboard/message relay |
| Klippy | `apps/klippy` | Clipboard history sync |
| Log analyzer | `apps/log-analyzer` | AI-assisted log triage feeding the alerting service |
| Liveness analyzer | `apps/liveness-analyzer` | Heartbeat dead-man's switch alerting when a client stops running |
| Object storage proxy | `apps/object-storage-proxy` | Upload proxy fronting S3-compatible or Azure Blob storage, plus the stream analysis worker |
| Reverse proxy | `apps/reverse-proxy` | Policy-gated reverse proxy in front of one upstream, logging every request |
| Secrets manager | `apps/secrets-manager` | Secret bundle/environment management |

## Packages

| Package | Directory | Purpose |
|---|---|---|
| Env | `packages/env` | Typed environment variable framework |
| Logger | `packages/logger` | Pluggable logging |
| Primitives | `packages/primitives` | Shared value types |
| Redis client | `packages/redis-client` | Pooled, prefix-namespaced handle on the shared Redis |
| Server bootstrap | `packages/server-bootstrap` | Shared Spring Boot wiring |
| Undertow bootstrap | `packages/undertow-bootstrap` | Shared lightweight HTTP runtime |
| Server parent | `packages/server-parent` | Parent POM for server modules |
| Server test support | `packages/server-test-support` | Shared Testcontainers base for integration tests |

## Other directories

| Directory | Purpose |
|---|---|
| `dashboard` | Local dev UI over the auth, secrets, and klippy servers — see `dashboard/README.md` |
| `benchmarks` | Ad-hoc measurement harnesses and dated result notes |
| `scripts` | Deploy-host and seeding scripts — see `scripts/README.md` |
| `db-init` | `all-services.sql`, the single source of the klippy/auth/secrets roles and databases |
| `nginx` | Reverse-proxy configs for the local stack |

## Build

Requires JDK 25+ and Maven 3.9+.

```bash
mvn package          # build everything
mvn test             # run all tests
mvn -pl <module> -am test   # build + test one module
```

Maven artifactIds are derived from directory paths (e.g. `apps/klippy/server` builds
`klippy-server`), so `mvn -pl :<artifactId>` and jar filenames line up with the tree. See
`CLAUDE.md` for the full module map and repo conventions.

See each app's README for run and configuration details.

## Lightweight server engine

The alerting and log-analyzer services can run on either the existing Spring Boot
web stack or embedded Undertow. (The Jarvis frame hub is Spring-only — it serves
SSE — while Jarvis person detection and the retention worker are plain programs
that serve no request routes.) The HTTP contracts and
business logic are shared; only the server runtime changes.

```bash
SERVER_ENGINE=undertow java -jar <service>-0.1.0-SNAPSHOT-exec.jar
SERVER_ENGINE=spring java -jar <service>-0.1.0-SNAPSHOT-exec.jar
```

`spring` remains the default. Undertow uses one I/O thread and five worker
threads, matching the expected maximum of five connected clients. The
database-backed services remain Spring-based for now.

## Log collection

Every server logs through `dev.orwell.logging.Logger`. The default sink writes human-readable
text to stdout for humans, and pushes structured entries straight to Loki from inside the JVM —
asynchronously, from a bounded queue, so a slow or unreachable Loki never delays a request path.
There is no log collector to scrape; set `LOKI_URL` (see `.env.example`), and entries arrive
labelled `{stream_type="app", app=..., level=...}`.

`LOGGER` chooses the sinks, the same way in every service: `console`, `disk`, `loki`,
`loki-with-fallback` (Loki, with a JSON-lines file catching only what Loki refused) or `both`.
The console is in all of them, so `docker logs` never goes quiet. Left unset it stays what it
always was — Loki when `LOKI_URL` is set, console with a startup warning when it is
not. Two services consume that stream through Grafana: `apps/log-analyzer` triages error logs
with an AI model, and `apps/liveness-analyzer` watches for client heartbeat lines and alerts when
a client stops beating. Label scheme and cardinality rules are in `apps/log-analyzer/README.md`;
the sink design is in `packages/logger/README.md`.
