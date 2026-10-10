# Orwell — agent guide

Multi-module Java 25 / Maven monorepo. Backend services under `apps/`, shared libraries under
`packages/`. Servers use Spring Boot 4.x, with selectable Undertow engines for lightweight services;
desktop clients are plain Java.

This file is the canonical guide. `CLAUDE.md` points here — don't write instructions into both.

---

## Read this first

The traps below are the ones that have actually cost time here. Each links to the section that
explains it. If you read nothing else, read this table.

| If you are about to… | Stop and check |
|---|---|
| Guess a Java package from a directory name | The packages predate the renames. [Module map](#module-map) |
| Add a `db`, `redis` or second Postgres anywhere | There is exactly one of each. [Storage](#storage-one-postgres-one-redis-one-minio) |
| Add a frame-ingest endpoint to a jarvis service | Only the hub takes frames. [Jarvis](#jarvis-frames) |
| Factor the two proxies' shared code into one place | The duplication is deliberate and was already unwound once. [Proxies](#the-two-proxies) |
| Use slf4j, `System.out`, or string-concatenate a log message | [Logging](#logging) |
| Build a bare `HttpClient` | Wrap it. [Outbound HTTP](#outbound-http) |
| Move or rename an auto-configured class | Update the `.imports` file or the beans silently vanish. [Server bootstrap](#server-bootstrap-and-engines) |
| "Restore" something you found referenced in old commits | It was probably deleted on purpose. [Deleted on purpose](#deleted-on-purpose--do-not-recreate) |
| Change behavior, endpoints, env vars or run commands | Update that app's README in the same change. [Docs](#documentation-policy) |
| Call the work finished | [Before you call it done](#before-you-call-it-done) |

**Three tracked directories are outside the Maven reactor**, so `mvn` says nothing about them and
the module map does not list them. Nothing else here builds, tests or deploys them:

- `fitness-tracker/` — a self-contained Next.js / TypeScript app with its own Drizzle migrations
  and `.gitignore`.
- `dashboard/` — a Python `http.server` proxy plus nginx config and static assets (see the root
  README).
- the Android module under `apps/klippy/clients` — builds with Gradle.

Treat them as unguarded: no CI runs there, so changes need manual verification.

---

## Build, test, run

```bash
mvn -q -DskipTests compile        # fast full-repo compile check
mvn test                          # all tests
mvn -pl :<artifactId> -am test    # one module + its deps
./scripts/stack.sh                          # the whole local stack (both compose files)
./scripts/stack.sh logs -f auth-server      # any compose subcommand, passed through
./scripts/stack.sh --storages-only up -d    # storage only: Postgres, Redis, MinIO
```

Things that will bite you, all verified the hard way:

- **`-am` is not optional** on a single-module build. `mvn -pl :insta test` fails to resolve
  `dev.orwell:*` snapshots unless the dependencies are built in the same reactor.
- **Filtering tests needs `-Dsurefire.failIfNoSpecifiedTests=false`**, not
  `-DfailIfNoSpecifiedTests=false`. With the wrong one, `-Dtest=Foo` fails the build on the first
  upstream module that has no `Foo`.
- **Tests need Docker.** Several suites use Testcontainers (real Postgres, real Redis) on the
  grounds that the behavior under test *is* the SQL. They bind no fixed port, which is why they are
  the one exception to the single-instance rule below.
- **`docker compose config` re-escapes `$` as `$$` on output**, so it cannot verify shell escaping
  inside an `entrypoint`. Run the container to check that.

Executable server jars are `<artifactId>-<version>-exec.jar` (Spring Boot `exec` classifier). Client
jars are `<artifactId>-<version>.jar`, except `klippy-file-locker` and `insta`, whose runnable jars
are the shaded `-exec.jar`.

---

## Module map

ArtifactIds derive from directory paths, compounding for nested modules
(`apps/jarvis/person-detection` → `jarvis-person-detection`). **Java packages predate the renames
and often do not match — use this table, don't guess.**

| Directory | artifactId | Java package |
|---|---|---|
| `apps/alerting` | `alerting` | `dev.orwell.alerting` |
| `apps/analyzer` | `analyzer` | `dev.orwell.analyzer` |
| `apps/api-key-cycler` | `api-key-cycler` | `dev.orwell.keycycler` (rotates API keys for one upstream; no database; standalone — shares no code with `reverse-proxy`) |
| `apps/auth/*` | `auth`, `auth-core`, `auth-http-{api,client,server}`, `auth-in-memory` | `dev.orwell.auth.*` |
| `apps/backup` | `backup` | `dev.orwell.backup` |
| `apps/google/gmail-general` | `gmail-general` | `dev.orwell.google.gmail` |
| `apps/health-monitor` | `health-monitor` | `dev.orwell.health` (polls other services' `/health`; Undertow, `/health` + `/targets`; no database) |
| `apps/insta` | `insta` | `dev.orwell.insta` (CLI; `insta ui` serves a read-only viewer) |
| `apps/insta-scraper` | `insta-scraper` | `dev.orwell.insta.scraper` (the `self-hosted` source behind `insta`; Undertow, `/connections` + `/health`) |
| `apps/jarvis` | `jarvis` (aggregator) | — |
| `apps/jarvis/frame-core` | `jarvis-frame-core` | `dev.orwell.bucket.frame` (library: the ingest envelope) |
| `apps/jarvis/frame-client` | `jarvis-frame-client` | `dev.orwell.bucket.frame.client` (library: the stream consumer) |
| `apps/jarvis/fixture-detection` | `jarvis-fixture-detection` | `dev.orwell.bucket.fixture` (stream watcher; region photometry — lamp/burner on-off; `/health` + `/fixtures`) |
| `apps/jarvis/frame-viewer` | `jarvis-frame-viewer` | `dev.orwell.bucket.viewer` (reads `frame_events`; a page + `/api/*`; video backfill; signs in against the auth server; needs Postgres) |
| `apps/jarvis/hub` | `jarvis-hub` | `dev.orwell.bucket.hub` (`/frames` + SSE; Spring-only; needs Postgres) |
| `apps/jarvis/person-detection` | `jarvis-person-detection` | `dev.orwell.bucket.person` (stream watcher; `/health` only) |
| `apps/jarvis/retention` | `jarvis-retention` | `dev.orwell.bucket.retention` (library, not a service) |
| `apps/jarvis/retention-worker` | `jarvis-retention-worker` | `dev.orwell.bucket.retention.worker` (headless, **no port**) |
| `apps/keeboarder/server` | `keeboarder-server` | `dev.orwell.keeboarder.server` |
| `apps/keeboarder/clients/*` | `keeboarder-{client-core,linux-client,mac-client}` | `dev.orwell.keeboarder.*` |
| `apps/klippy/server` | `klippy-server` | `dev.orwell.server` |
| `apps/klippy/utils` | `klippy-utils` | `dev.orwell.utils` |
| `apps/klippy/clients/*` | `klippy-client-core`, `klippy-dummy-client`, `klippy-file-locker`, `klippy-linux-client`, `klippy-mac-client`, `klippy-offline-sync-client` | `dev.orwell.clients.*` |
| `apps/liveness-analyzer` | `liveness-analyzer` | `dev.orwell.liveness` |
| `apps/log-analyzer` | `log-analyzer` | `dev.orwell.loganalyzer` |
| `apps/object-storage-proxy` | `object-storage-proxy` | `dev.orwell.objectstorage.proxy` |
| `apps/reverse-proxy` | `reverse-proxy` | `dev.orwell.reverseproxy` |
| `apps/secrets-manager/server` | `secrets-manager-server` | `dev.orwell.secrets` |
| `apps/secrets-manager/client` | `secrets-manager-client` | `dev.orwell.secrets.client` |
| `packages/env/{core,http}` | `env-core`, `env-http` | `dev.orwell.env`, `dev.orwell.env.http` |
| `packages/http-client` | `http-client` | `dev.orwell.http` |
| `packages/logger` | `logger` | `dev.orwell.logging` |
| `packages/primitives` | `primitives` | `dev.orwell.primitives` |
| `packages/redis-client` | `redis-client` | `dev.orwell.redis` |
| `packages/server-bootstrap` | `server-bootstrap` | `dev.orwell.bootstrap.{launch,auth,health,web,logging}` |
| `packages/undertow-bootstrap` | `undertow-bootstrap` | `dev.orwell.undertow` |
| `packages/server-parent` | `server-parent` (parent POM) | — |
| `packages/server-test-support` | `server-test-support` | `dev.orwell.testing` |
| `packages/test-support` | `test-support` | `dev.orwell.testing.http` |

Grep by **package** when tracing code, by **artifactId** when tracing builds. Jarvis lives under
`dev.orwell.bucket.*`; klippy-server under `dev.orwell.server`.

---

## Logging

**Logging goes through `dev.orwell.logging.Logger`** (`packages/logger`) — a hand-written
`@FunctionalInterface`, deliberately **not** slf4j. `log(LogEntry)` is the single abstract method (so
a test double can be a lambda); `trace/debug/info/warn/error` are defaults, each with an optional
`Map<String, Object>` metadata overload. Levels are `TRACE|DEBUG|INFO|WARN|ERROR`. See
`packages/logger/README.md` for which sink to use.

**Records are structured — detail goes in the metadata map, not the message.** Write
`logger.info("Registered with the server.", Map.of("clientId", id))`, not string concatenation.
There is no `{}` formatting and **no `(String, Throwable)` overload**: pass exception detail as
metadata (`metadata.put("error", exception.toString())`). `LogEntry` permits null metadata *values*
on purpose — `exception.getMessage()` is null more often than people expect, and a logging call must
never be what brings a caller down. That is why it copies into a `LinkedHashMap` rather than
`Map.copyOf`; keep it that way.

**Everything a `Logger` produces is labelled `stream_type="app"` in Loki, and one thing deliberately
is not.** `LoggerSetup.heartbeatChannel(streamType, …)` opens a second Loki-only channel for records
that are high-volume and low-information — a liveness heartbeat, one line per interval forever — so they
stay out of the stream `apps/log-analyzer` triages for errors. `apps/health-monitor` uses it so
`apps/liveness-analyzer` can alert on the absence of its beat. The grouping identity (`clientId`) goes
in the line **body**, never a label: the cardinality rule in `apps/log-analyzer/README.md`. Don't open a
second stream for ordinary app records — the app stream is where they belong.

**Sinks compose.** `ConsoleLogger` (human-readable, stdout/stderr), `JsonLogger` (JSON lines to a
file), `LokiLogger` (async batched push from a bounded queue — never blocks the caller),
`CustomLogger` (named `.txt` in the log directory), `CompositeLogger` to fan one record out, and
`FailSafeLogger` to wrap the lot so a sink failure can't turn a request into an HTTP 500. Which
sinks you get is a choice of what you construct, not a change to any call site.

**Loggers are passed in.** Spring components take `Logger` as a constructor parameter (the bean
comes from `dev.orwell.bootstrap.logging.LoggerConfiguration`); clients build one in `main` and pass
it down. `PollInterval` holds a static `CustomLogger` — that is the one outlier, not the pattern to
copy.

**Which sinks a process gets is the `LOGGER` env var's choice, not per-app code.**
`LoggerSetup.fromConfiguration(appName, LOGGER, LOKI_URL, LOKI_TENANT_ID)` is the one place the tree
is assembled — `console`, `disk`, `loki`, `loki-with-fallback` (Loki, with the file catching only
what Loki refused) or `both`. `ConsoleLogger` is in every mode; a mode names what is added to it.
**Do not hand-roll an `if (LOKI_URL is set)` in a new `main`** — call the factory, as
`jarvis-person-detection` and `jarvis-retention-worker` do. It returns a `ManagedLogger`: the
`Logger` plus the `close()` that flushes Loki's queue. Unset, the default is unchanged — Loki when
`LOKI_URL` is set, console with a warning when not. Override the whole thing by declaring your own
`Logger` bean. **Servers write no app log file by default**; a `.jsonl` appears only where `LOGGER`
asks for one.

### Three hard prohibitions

1. **No synchronous network or database sink.** It would put a round trip on every request path, and
   `FailSafeLogger` protects against a sink being *down*, not against it being *slow*.
   `DatabaseLogger` was deleted for this reason. reverse-proxy's `JdbcAuditStore` is **not** a
   counter-example and must not become a `Logger`: it is an app-level store the endpoint writes to
   *alongside* the logger, behind a bounded queue drained by one thread, and it drops records rather
   than delaying a request. An app that wants records in a table does that — it does not get a
   database sink.
2. **No `System.out` / `System.err`** outside the paths that run before a logger can exist
   (`AppServer`, `AuthenticationStrategyConfiguration`, undertow `ServerRuntime`, `env-http`
   `EnvLoader`) or the last-resort reporting inside a failing sink itself (`FailSafeLogger`,
   `LokiLogger`). Anywhere else it is a regression.
3. **No slf4j or logback from repo code.** Spring Boot puts them on the server classpath
   transitively — that is unavoidable and is where Spring's *own* framework logging goes. Don't use
   them, and don't try to exclude them.

---

## Outbound HTTP

**Every outbound client is wrapped in `LoggingHttpClient`.**
`LoggingHttpClient.wrap(client, logger)` (`packages/http-client`) writes one record per request —
`method`, `uri`, `status`, `durationMs` — through the `Logger` the component already has, so a
service's outbound calls land in the stream `LOGGER` already points at. It never logs headers,
bodies or query strings (`Authorization` is on nearly every request and tokens travel in query
strings), and never lets a sink failure break a request.

That includes the Spring `RestClient` ones: they name `JdkClientHttpRequestFactory` explicitly so
the client underneath is the wrapped one. A new one does the same.

**Six deliberate exceptions**, each for its own reason:

- `reverse-proxy`'s `UpstreamClient` — the endpoint already writes a record per proxied request, so
  wrapping would double it.
- `api-key-cycler`'s `UpstreamClient` — same reason; it is the cycler's own copy of that endpoint.
- `LokiLogger` — a sink logging its own pushes through itself is a loop.
- `env-http`'s `EnvLoader` — it runs before a logger can exist.
- `object-storage-proxy`'s `AnalysisWorker` — a per-frame push loop at video rates, not a
  request/response service. One record per request is a line per frame, which silently defeated the
  `SEND_FAILURE_LOG_INTERVAL` throttle it already had; that throttled line is its outbound record.
- `health-monitor`'s `HealthProbe` — a poll loop on a timer. One request per target per interval is
  ~40,000 records a day saying a healthy service is still healthy, plus a WARN per round for every
  round an outage lasts. The outcome of each probe is already on `/targets`, and the part worth a line
  — the transition — is written at ERROR by `HealthMonitorService`. Its *alert* client is wrapped: a
  few requests a year, where one record per request is the right granularity.

The shape of every exception is the same: **wrapping would produce a record per request where one
already exists, or where one per request is the wrong granularity.** A new client that is neither of
those wraps.

Client libraries reached from CLIs (`SecretsManagerClient`, `HttpAuthenticationStrategy`,
`ClientAuthSession`, `ObjectStorageProxyClient`) keep a no-`Logger` constructor defaulting to a
`ConsoleLogger`, so a caller that says nothing still gets records; pass the process's own `Logger`
where there is one.

**Always set timeouts.** Both a connect timeout on the client and a request timeout on the request —
a connect timeout alone does not bound a server that accepts and then goes quiet. That failure mode
has taken down two services here; see the fixed entries in [`ISSUES.md`](ISSUES.md).

### `ServiceHeaderHttpClient` — intended design, not working today

`ServiceHeaderHttpClient.wrap(client, "<app name>")` stamps a `Service` header naming the sender, so
a receiver logs that name instead of a container address that means something else after the next
restart. **Nothing calls it.** Two things therefore do not work: `request_audit.service` is NULL for
every request an orwell service makes, and a service does not forward the `X-Request-Id` that
`RequestIdFilter` bound to its serving thread, so a caller and its callee mint different ids. (The
reverse proxy is the exception on the id — `UpstreamClient` sets that header explicitly, so a
*proxied* hop does correlate. Service-to-service calls do not.)

**Do not cite either feature as working.** [`ISSUES.md`](ISSUES.md) §2 is its status. Adopting it is
one line per call site: `LoggingHttpClient.wrap(ServiceHeaderHttpClient.wrap(client, name), logger)`.
When you do: a header already present wins, which is why a **relay must not wrap** — the reverse
proxy's `UpstreamClient` deliberately doesn't, so the original caller's name survives the hop. It is
attribution, not authentication: anything can claim any name, and auth is still the auth service's
job.

---

## Configuration

Env vars are declared through the typed `EnvSchema` framework (`packages/env`); each app exposes an
`*Envs` class. Common keys (`SERVER_ADDRESS`, `SERVER_PORT`, `LOGGING_FILE_NAME`, `AUTH_BASE_URL`,
`LOGGER`, `LOKI_URL`, `LOKI_TENANT_ID`) come from `AppServerEnv` — **don't redeclare them per app.**

**Env keys are per app and never shared across two processes.** `PROXY_*` for the audit proxy,
`CYCLER_*` for the cycler, and so on. This is the same rule that retired every `DETECTION_*` key
when one service became four: a key that two processes read is a key whose meaning depends on which
container you're in.

---

## Server bootstrap and engines

Spring server engines start via `AppServer` + `AppServerEnv` (`packages/server-bootstrap`,
`dev.orwell.bootstrap.launch`). Shared auto-configs (health endpoint, 401 guard, logger, auth
strategy) are registered in
`packages/server-bootstrap/src/main/resources/META-INF/spring/…AutoConfiguration.imports` — **if you
move or rename any of those classes, update that file or the beans silently vanish.** There are four
entries; they are plain class names with no compiler check behind them.

Alerting and log-analyzer also support Undertow through `packages/undertow-bootstrap`. Their neutral
main classes select the engine with `SERVER_ENGINE=spring|undertow`; keep business logic shared
between both. **Jarvis no longer plays here:** `jarvis-hub` serves SSE and so is Spring-only with a
single main class (do not add an Undertow path), `jarvis-person-detection` is a plain program using
`UndertowHttp` for one `/health` route, `jarvis-frame-viewer` is a plain program serving a page and
its JSON routes on `UndertowHttp` (no Spring, so no second engine to choose),
`jarvis-fixture-detection` is the same shape, and `jarvis-retention-worker` runs no HTTP server at
all. `apps/health-monitor` is a plain program too, for the same reason.

**A `/health` endpoint lives at the ROOT of every app, never under its route prefix.**
`auth-server` runs with `AUTH_ROUTE_PREFIX=/auth` and its health endpoint is still `/health` —
`nginx/all-services.conf` maps `/auth/health` to `auth-server:80/health`, and that file is the
authoritative list of where each one is. Both `SharedHealthController` and `UndertowHttp.health` map it
at the root; don't move one under a prefix, and note `apps/health-monitor`'s target list assumes this.

**`apps/alerting` is the only thing in the repo that sends mail** — it holds the single
`Transport.send`, and every alert any service raises becomes an email there or nowhere. Don't add SMTP
to a second app; raise an alert to `POST /alerts` instead. (`apps/google/gmail-general` is not a
counter-example: it *reads* mailboxes over IMAP and forwards to webhooks, and its `send` is an HTTP
call.) It speaks **STARTTLS on 587 only** — `SMTP_USE_TLS` sets `mail.smtp.starttls.enable`, so
implicit TLS on 465 would need a change in `SmtpConfig`.

**A 200 from it does not mean an email was sent.** It accepts
and records every alert, and only mails one when `ALERT_EMAIL_ENABLED`, `ALERT_EMAIL_TO` and the
`SMTP_*` settings are all present on *that* service — otherwise it answers 200 with
`emailSent: false`. A caller that treats the status code as success reports cover it does not have, so
**read the response**: `apps/health-monitor` counts `emailsSentTotal` apart from `alertsSentTotal` and
surfaces `lastAlertProblem`. The subject is `[Alert] {event} from {source}` and the body is the whole
alert JSON, so `event` and `source` are the two keys worth setting well.

There is deliberately **no per-request recipient**: `/alerting/alerts` is proxied by nginx on
`0.0.0.0:8080`, so a `to` field would let anything that can reach that port send mail anywhere through
your SMTP credentials. Alerts needing a different address get a second alerting deployment, the way the
repo already runs two auth servers.

**Two apps answer "is it up", and they are not interchangeable.** `apps/health-monitor` actively probes
other services' `/health` over HTTP and alerts on the transitions; `apps/liveness-analyzer` reads Loki
for heartbeat lines desktop *clients* emit and alerts on their absence. Neither subsumes the other — a
client behind a home router cannot be polled, and a server can be up while broken. A new "is X alive"
check belongs in whichever matches how X can be observed, not in a third app. Neither replaces the
per-service `healthcheck` blocks in `docker-compose.all-services.yml` either: those restart a container
and tell nobody.

---

## Storage: one Postgres, one Redis, one MinIO

**There is exactly one Postgres and one Redis**, defined in `docker-compose.storages.yml` (services
`db` and `redis`). Nothing else in the repo may create one: the per-app compose files and
`apps/object-storage-proxy/scripts/local-stack.sh` all use this instance, and
`db-init/all-services.sql` is the single source of the roles and databases. Ephemeral Testcontainers
are the one exception — they bind no fixed port. If you find another Postgres or Redis being
created, it is a straggler to remove, not a setup to preserve.

The two compose files are a split, not a copy: `db`, `redis` and `minio` are defined **only** in
`docker-compose.storages.yml`, `docker-compose.all-services.yml` holds only applications, and the
full stack is `-f` both under one compose project. **Do not add a `db` or `redis` block to the
applications file to make it start on its own** — that recreates the duplication this split removed.
Use `./scripts/stack.sh`, which passes both files.

`minio-backup` (in the applications file, behind the `backup` compose profile) mirrors a MinIO
bucket to a remote S3 bucket on a timer. It is off unless the profile is selected; see `.env.example`
for the destination-bucket settings that make it a backup rather than a mirror.

**Because that Redis is shared, every app namespaces its keys with a prefix** rather than taking a
numbered database: `ws:` for keeboarder-server, `insta:` for insta. Redis databases look like
workspaces but share one memory pool, one AOF/RDB file and one eviction policy; `FLUSHALL` crosses
all of them, and Redis Cluster supports only database 0. So a new app gets a prefix, not a database.
It runs with `--appendonly yes` so a restart does not drop paid-for cache entries.

**Redis is reached through `dev.orwell.redis.RedisClient`** (`packages/redis-client`), which owns the
`JedisPool` and applies its prefix to every key — so the rule above holds by construction. It turns a
driver failure into an unchecked `RedisOperationException` and deliberately does not decide whether
that is fatal: insta's cache catches it and answers a miss, keeboarder lets it propagate so a client
learns its registration failed. A new call site takes a `RedisClient` with its own prefix, never a
`JedisPool`; add commands to that class as needed. See `packages/redis-client/README.md`.

---

## Jarvis: frames

**Only `jarvis-hub` takes frames over HTTP.** Anything else that wants frames *watches* the hub
through `jarvis-frame-client`, so a producer pushes each frame exactly once. Do not add a frame
ingest endpoint to another service — that is the shape this deliberately replaced. A new watcher
takes `FrameStreamClient` with its own durable `subscription` name rather than writing an SSE reader.

`jarvis-frame-viewer` is **not** a counter-example in either direction, and both halves are
deliberate:

- It *reads* `frame_events` directly rather than subscribing, because the rule is about frames
  *arriving* and a viewer asks about *history* — one indexed seek per navigation step, so a browser
  scrubbing back through an hour of footage stays off the hub's heap. `RetentionWorker` set that
  precedent; the viewer opens its datasource read-only.
- Its video upload (`POST /api/uploads`) *pushes to the hub's `POST /frames`* rather than inserting
  rows, precisely because of this rule: rows written elsewhere would take ids outside the hub's
  sequence and skip the live fan-out, so `jarvis-person-detection` would never see a backfilled
  frame.

**A new reader of stored frames may query the table; anything that makes frames exist goes through
the hub.**

The viewer is also the repo's third caller of the auth server's login-plus-cookie flow, after
`object-storage-proxy`'s `/admin` and secrets-manager's. A non-Spring app cannot use the
`@RequireAuthentication` interceptor from `packages/server-bootstrap`, so `ViewerAuth` guards its
Undertow routes with `HttpAuthenticationStrategy` directly. Copy that shape rather than inventing a
fourth — and note it accepts `X-Client-Id` + `Authorization: Bearer` as well as the cookie, so a
script needs no session.

### `capturedAt`

**`POST /frames` accepts an optional `capturedAt`, and a live producer must leave it out.** Absent,
the hub stamps `Instant.now()` on arrival — the normal path, and the receiver's clock is the better
one. Present, the claimed time is stored, which is what makes backfilling a recording possible
instead of landing an hour of footage on a single instant. It is asserted and not verifiable (as
`source` already is), a bad value is a 400 rather than a silent fall back to "now", and the **id is
unaffected** either way, so id order stays arrival order however far back a backfill is dated.
**Don't add validation that a `capturedAt` is "recent"** — old footage is the use case.

### Detection is measured where it can be, not modelled

`jarvis-fixture-detection` decides whether a lamp or burner is on with photometry over a configured
region — two thresholds with hysteresis and a confirmation count — because for a fixed camera that is
a *measurement*: deterministic, testable against synthetic frames, and free. It carries **no ML
runtime, no native library and no GPU dependency**, and that is a property to preserve. A vision
model belongs on the open question ("is anything unusual here"), never on "is this rectangle brighter
than 40". If you add a model to jarvis, add it as its own watcher behind a cheap gate; do not put one
inside this app.

Note `HogPersonDetector` in `jarvis-person-detection` is **not** HOG and does not detect people — it
is a brightness heuristic standing in for a real detector, recorded as such in `CODE_REFACTOR.md`.

---

## The two proxies

**There are two reverse proxies and they are deliberately separate codebases.**
`apps/reverse-proxy` records and refuses requests; `apps/api-key-cycler` rotates API keys for one
upstream. Each carries its own `ProxyController`, `ProxyRequest`, `ProxyResponse`, `UpstreamClient`
and `ProxyEndpoint`. **This duplication is a decision, not an oversight** — a shared
`packages/proxy-core` was built and then deliberately unwound, so do not re-extract one. A third
proxy is a third standalone app.

Know what that costs before touching either. The hop-by-hop header list (RFC 9110 §7.6.1),
`X-Request-Id` propagation across the hop, the two-clock timing that separates the origin's cost from
the proxy's, and the body-buffer ceiling **all exist twice**. A fix to any of those in one app must
be applied to the other **by hand** — nothing will tell you.

They have already diverged on purpose: only the cycler has a retry loop, `Attempt` and `attempts` on
its record; only the audit proxy has policies, `ProxyAuditRecord` and Postgres.

**Nothing a proxy says about itself may contain a credential.** `signature()` reaches a log line and,
in the audit proxy, a database column; `healthDetails()` reaches an HTTP response. `ApiKeyPool`
reports the key *count*, and `ApiKey` overrides `toString()` so the record default cannot print a
secret into a stack trace. Exception messages count as "says about itself" — a key that reaches an
`IllegalArgumentException` reaches the logs.

---

## Naming and renaming

The app is **klippy** everywhere — identifiers, files, sockets, log names, databases, docs. There is
no dual spelling to preserve; an older one is a straggler to fix, not a contract to protect.

A rule of thumb when renaming anything here: renaming a **Java identifier** is free — the compiler
proves it. Renaming a **string** is where the risk lives, because a string is usually someone else's
contract: a file on a user's disk, a socket path, a log name, a database. Check which of the two you
have before touching it.

---

## Deleted on purpose — do not recreate

Old commits reference all of these. Ignore them.

| Gone | Why, in one line |
|---|---|
| `apps/combined-server` | Superseded; do not recreate. |
| `apps/jarvis/motion` (`POST /motion`, `FrameChangeDetector`, `MotionService`) | Deciding whether a frame is interesting is a producer's job now. |
| `apps/jarvis/detection` (as one deployable) | Split into `hub`, `person-detection`, `retention-worker` plus libraries, because its endpoints had unrelated cost profiles and one container had to be sized for the most expensive. `frame-viewer` and `fixture-detection` were added later for the same reason. `apps/jarvis/README.md` carries the `DETECTION_*` rename table. |
| `packages/proxy-core` | Built, then deliberately unwound. See [the two proxies](#the-two-proxies). |
| `DatabaseLogger` | A synchronous sink on the request path. See [logging](#logging). |
| `apps/klippy/devops` (Terraform + cloud-init for Azure) | No longer used. Jarvis's AWS Terraform/EC2 stack under `bucket/deployment` went the same way when the repo dropped AWS-specific infrastructure. |
| `scripts/seed-secrets.sh` | Drifted out of step: aborted on a renamed variable, could not re-run, left 44 envs in no bundle. A fresh secrets manager is populated through its own `/admin` API. |

`jarvis-fixture-detection` is **not** the motion service returning: it takes no frames over HTTP,
compares a frame to *thresholds* rather than to the previous frame, and answers a question about
named fixtures rather than about change. A filter inside one watcher is allowed; a motion endpoint is
not.

---

## Documentation policy

Most apps have their own `README.md`. **Whenever you change an app's behavior, endpoints, environment
variables, run commands, or build output, update that app's README in the same change** — and the
root `README.md` table if the app's purpose or name changed.

Stale docs are worse than missing docs: agents and humans both trust them. If you delete or rename
something, grep the `*.md` files for references to it before you finish.

**Two root-level tracking docs, and only two.** `CODE_REFACTOR.md` is the refactor backlog —
duplication, debt, work that is wanted. [`ISSUES.md`](ISSUES.md) is the defect list — things that are
wrong now. When you finish an item, move it to the "done" note at the top of `CODE_REFACTOR.md` or
strike it through in `ISSUES.md` (which keeps fixed entries, so the file records what was decided and
not just what is open). New debt and new defects go in those two. **Do not create a third planning
doc at the root** — this file is a guide, not a backlog.

---

## Before you call it done

1. `mvn -q -DskipTests compile` passes.
2. Tests for what you touched pass — `mvn -pl :<artifactId> -am test`. New behavior has a test; a
   bug fix has a test that fails without the fix.
3. The app's `README.md` reflects any change to behavior, endpoints, env vars, run commands or build
   output; the root README table too if the purpose or name changed.
4. New env keys are in the app's `*Envs` class **and** its `.env.example`, with a comment saying what
   breaks if they are wrong.
5. If you added a table, the test fixture that drops tables between tests knows about it.
6. If you changed a compose service, `docker compose config` parses and you ran the thing.
7. New defects go in `ISSUES.md`, new debt in `CODE_REFACTOR.md` — not in a new file.
8. If you touched one of the two proxies, decide explicitly whether the other needs the same change,
   and say which you chose.
