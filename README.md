# orwell

Multi-module monorepo for a suite of backend services and desktop clients.

## Apps

| App | Directory | Purpose |
|---|---|---|
| Alerting | `apps/alerting` | Email alert dispatch with per-source cooldowns |
| Analyzer | `apps/analyzer` | Receives Gmail messages from `gmail-general` at `POST /analyzer/email` and flags subjects containing `login` |
| Auth | `apps/auth` | Client identity and token management |
| Backup | `apps/backup` | Postgres backup runner |
| Google | `apps/google` | Gmail integration service |
| Insta | `apps/insta` | CLI for public Instagram follower/following lookups via the Apify marketplace |
| Insta scraper | `apps/insta-scraper` | self-hosted follower/following source for `insta`, driving a logged-in Instagram session |
| Jarvis | `apps/jarvis` | Surveillance: frame hub (SSE), person detection, fixture detection (lamp/stove on-off), retention worker, frame viewer (browse stored frames, backfill videos), recorder clients |
| Keeboarder | `apps/keeboarder` | Keyboard/message relay |
| Klippy | `apps/klippy` | Clipboard history sync |
| Log analyzer | `apps/log-analyzer` | AI-assisted log triage feeding the alerting service |
| Liveness analyzer | `apps/liveness-analyzer` | Heartbeat dead-man's switch alerting when a client stops running |
| Health monitor | `apps/health-monitor` | Polls every service's `/health` and alerts on the transitions — the active counterpart to the liveness analyzer's dead-man's switch |
| Object storage proxy | `apps/object-storage-proxy` | Upload proxy fronting S3-compatible or Azure Blob storage, plus the stream analysis worker |
| Reverse proxy | `apps/reverse-proxy` | Policy-gated reverse proxy in front of one upstream, with a queryable `request_audit` trail of every request |
| API key cycler | `apps/api-key-cycler` | Reverse proxy holding several API keys for one upstream, rotating to the next when one is rate-limited or revoked. Standalone — shares no code with the reverse proxy |
| Secrets manager | `apps/secrets-manager` | Secret bundle/environment management |

## Packages

| Package | Directory | Purpose |
|---|---|---|
| Env | `packages/env` | Typed environment variable framework |
| HTTP client | `packages/http-client` | `HttpClient` wrappers: one records every outbound request (used everywhere), one stamps a `Service` header naming the caller (built and tested, not yet wired up — see `ISSUES.md` §2) |
| Logger | `packages/logger` | Pluggable logging |
| Primitives | `packages/primitives` | Shared value types |
| Redis client | `packages/redis-client` | Pooled, prefix-namespaced handle on the shared Redis |
| Server bootstrap | `packages/server-bootstrap` | Shared Spring Boot wiring |
| Undertow bootstrap | `packages/undertow-bootstrap` | Shared lightweight HTTP runtime |
| Server parent | `packages/server-parent` | Parent POM for server modules |
| Server test support | `packages/server-test-support` | Shared Testcontainers base and test doubles for integration tests |
| Test support | `packages/test-support` | Dependency-free test helpers, so lean client modules can use them too — `JsonStubServer` for tests that need a real HTTP server |

## Other directories

| Directory | Purpose |
|---|---|
| `dashboard` | Local dev UI over the auth, secrets, and klippy servers — see `dashboard/README.md` |
| `fitness-tracker` | Standalone Next.js / TypeScript app with its own Drizzle migrations. Not in the Maven reactor and not part of the stack — nothing else here builds, tests or deploys it |
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
web stack or embedded Undertow. The HTTP contracts and business logic are shared; only the server
runtime changes. Jarvis does not play here: the frame hub is Spring-only because it serves SSE, and
its four siblings are plain programs — the retention worker serves nothing at all, while person
detection, fixture detection and the frame viewer serve their routes on the Undertow runtime directly,
so there is no second engine for them to choose between.

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
