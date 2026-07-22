# Orwell monorepo — agent guide

Multi-module Java 25 / Maven monorepo: backend services under `apps/`, shared libraries under
`packages/`. Servers use Spring Boot 4.x, with selectable Undertow engines for lightweight
services; desktop clients use plain Java.

## Build and test

```bash
mvn -q -DskipTests compile        # fast full-repo compile check
mvn test                          # all tests
mvn -pl :<artifactId> -am test    # one module + its deps (artifactIds match dir names)
docker compose -f docker-compose.all-services.yml up -d    # the whole local stack
```

Executable server jars are built as `<artifactId>-<version>-exec.jar` (Spring Boot `exec`
classifier). Client jars are `<artifactId>-<version>.jar`, except `klippy-file-locker`, whose
runnable jar is the shaded `<artifactId>-<version>-exec.jar`.

## Module map

ArtifactIds are derived from directory paths (nested modules compound the path:
`apps/jarvis/detection` → `jarvis-detection`, `apps/keeboarder/server` → `keeboarder-server`).
Java packages predate the renames and do NOT always match — use this table, don't guess:

| Directory | artifactId | Java package |
|---|---|---|
| `apps/alerting` | `alerting` | `dev.orwell.alerting` |
| `apps/analyzer` | `analyzer` | `dev.orwell.analyzer` |
| `apps/auth/*` | `auth`, `auth-core`, `auth-http-{api,client,server}`, `auth-in-memory` | `dev.orwell.auth.*` |
| `apps/backup` | `backup` | `dev.orwell.backup` |
| `apps/google/gmail-general` | `gmail-general` | `dev.orwell.google.gmail` |
| `apps/jarvis` | `jarvis` (aggregator) | — |
| `apps/jarvis/bucket/proxy` | `jarvis-bucket-proxy` | `dev.orwell.bucket.proxy` |
| `apps/jarvis/detection` | `jarvis-detection` | `dev.orwell.bucket.detection` |
| `apps/keeboarder/server` | `keeboarder-server` | `dev.orwell.keeboarder.server` |
| `apps/keeboarder/clients/*` | `keeboarder-{client-core,linux-client,mac-client}` | `dev.orwell.keeboarder.*` |
| `apps/klippy/server` | `klippy-server` | `dev.orwell.server` |
| `apps/klippy/utils` | `klippy-utils` | `dev.orwell.utils` |
| `apps/klippy/clients/*` | `klippy-client-core`, `klippy-dummy-client`, `klippy-file-locker`, `klippy-linux-client`, `klippy-mac-client`, `klippy-offline-sync-client` | `dev.orwell.clients.*` |
| `apps/log-analyzer` | `log-analyzer` | `dev.orwell.loganalyzer` |
| `apps/secrets-manager/server` | `secrets-manager-server` | `dev.orwell.secrets` |
| `apps/secrets-manager/client` | `secrets-manager-client` | `dev.orwell.secrets.client` |
| `packages/env/{core,http}` | `env-core`, `env-http` | `dev.orwell.env`, `dev.orwell.env.http` |
| `packages/logger` | `logger` | `dev.orwell.logging` |
| `packages/primitives` | `primitives` | `dev.orwell.primitives` |
| `packages/server-bootstrap` | `server-bootstrap` | `dev.orwell.bootstrap.{launch,auth,health,web,logging}` |
| `packages/undertow-bootstrap` | `undertow-bootstrap` | `dev.orwell.undertow` |
| `packages/server-parent` | `server-parent` (parent POM) | — |
| `packages/server-test-support` | `server-test-support` | `dev.orwell.testing` |

## Conventions and history

- The app is **klippy** everywhere — identifiers, files, sockets, log names, databases, docs.
  There is no dual spelling to preserve; if you find an older one, it is a straggler to fix, not
  a contract to protect.
- A rule of thumb worth keeping when renaming anything here: renaming a **Java/Kotlin identifier**
  is free — the compiler proves it. Renaming a **string** is where the risk lives, because a
  string is usually someone else's contract: a file on a user's disk, a socket path, a log name, a
  database. Check which of the two you have before touching it.
- Jarvis's Java packages live under `dev.orwell.bucket.*`; klippy-server's under
  `dev.orwell.server`. Grep by package when tracing code, by artifactId when tracing builds.
- `apps/combined-server` was deleted deliberately. Do not recreate it; ignore references to it
  in old commits.
- `apps/klippy/devops` (Terraform + cloud-init for Azure) was deleted deliberately — it is no
  longer used. Don't recreate it; ignore references to it in old commits. `apps/jarvis`'s AWS
  Terraform/EC2 stack under `bucket/deployment` was likewise removed when the repo dropped all
  AWS-specific infrastructure — don't recreate it either.
- **There is exactly one Postgres and one Redis**, defined in `docker-compose.all-services.yml`
  (services `db` and `redis`). Nothing else in the repo may create one: the per-app compose files
  and `apps/jarvis/.../local-stack.sh` all use this instance, and `db-init/all-services.sql` is
  the single source of the `klippy`/`auth`/`secrets` roles and databases. Ephemeral Testcontainers
  in tests are the one exception — they bind no fixed port. If you find another Postgres or Redis
  being created, it is a straggler to remove, not a setup to preserve.

## Repo conventions

- Spring server engines start via `AppServer` + `AppServerEnv`
  (`packages/server-bootstrap`, `dev.orwell.bootstrap.launch`). Shared auto-configs (health
  endpoint, 401 guard, logger, auth strategy) are registered in
  `packages/server-bootstrap/src/main/resources/META-INF/spring/…AutoConfiguration.imports` —
  if you move or rename any of those classes, update that file or the beans silently vanish.
- Alerting, log-analyzer, and Jarvis detection also support Undertow through
  `packages/undertow-bootstrap`. Their neutral main classes select the engine with
  `SERVER_ENGINE=spring|undertow`; keep business logic shared between both engines.
- **Logging goes through `dev.orwell.logging.Logger`** (`packages/logger`) — a hand-written
  `@FunctionalInterface`, deliberately **not** slf4j. `log(LogEntry)` is the single abstract
  method (so a test double can be a lambda); `trace/debug/info/warn/error` are defaults, each
  with an optional `Map<String, Object>` metadata overload. Levels are `TRACE|DEBUG|INFO|WARN|
  ERROR`. See `packages/logger/README.md` for which sink to use.
- **Records are structured — put detail in the metadata map, not in the message.** Write
  `logger.info("Registered with the server.", Map.of("clientId", id))`, not string
  concatenation. There is no `{}` formatting and **no `(String, Throwable)` overload**: pass
  exception detail as metadata (`metadata.put("error", exception.toString())`). `LogEntry`
  permits null metadata *values* on purpose — `exception.getMessage()` is null more often than
  people expect, and a logging call must never be what brings a caller down. That is why it
  copies into a `LinkedHashMap` rather than `Map.copyOf`; keep it that way.
- **Sinks compose.** `ConsoleLogger` (human-readable, stdout/stderr), `JsonLogger` (JSON lines
  to a file), `LokiLogger` (async batched push to Loki from a bounded queue — never blocks the
  caller), `CustomLogger` (named `.txt` in the log directory), `CompositeLogger` to fan one
  record out, and `FailSafeLogger` to wrap the lot so a sink failure can't turn a request into
  an HTTP 500. Which sinks you get is a choice of what you construct, not a change to any call
  site.
- **Loggers are passed in.** Spring components take `Logger` as a constructor parameter (the
  bean comes from `dev.orwell.bootstrap.logging.LoggerConfiguration`); clients build one in
  `main` and pass it down. `PollInterval` holds a static `CustomLogger` — that is the one
  outlier, not the pattern to copy.
- The Spring `Logger` bean defaults to `FailSafeLogger(Composite(Console, Loki))` when
  `LOKI_URL` is set, and console-only with a warning when it isn't. Override by declaring your
  own `Logger` bean. `LOKI_URL`/`LOKI_TENANT_ID`/`LOGGING_FILE_NAME` are common keys on
  `AppServerEnv`. **Servers no longer write an app log file by default** — the default sink is
  console plus Loki push.
- A synchronous network or database sink is not an acceptable addition: it would put a round
  trip on every request path, and `FailSafeLogger` protects against a sink being *down*, not
  against it being *slow*. `DatabaseLogger` was deleted for this reason — don't reintroduce it.
- Never add `System.out`/`System.err` outside the paths that run before a logger can exist
  (`AppServer`, `AuthenticationStrategyConfiguration`, undertow `ServerRuntime`, `env-http`
  `EnvLoader`) or the last-resort reporting inside a failing sink itself (`FailSafeLogger`,
  `LokiLogger`). Anywhere else it is a regression.
- Spring Boot still puts slf4j and logback on the server classpath transitively — that is
  unavoidable and is where Spring's *own* framework logging goes. Do not use them from repo
  code, and do not try to exclude them.
- Env vars are declared through the typed `EnvSchema` framework (`packages/env`); apps expose
  an `*Envs` class. Common keys (`SERVER_ADDRESS`, `SERVER_PORT`, `LOGGING_FILE_NAME`,
  `AUTH_BASE_URL`) come from `AppServerEnv` — don't redeclare them per app.
- `CODE_REFACTOR.md` is the single refactor backlog. When you finish an item, move it to the
  "done" note at the top; when you find new debt, add it there rather than creating a new
  planning doc at the root.

## Documentation policy — keep READMEs current

Most apps have their own `README.md`. **Whenever you change an app's behavior, endpoints,
environment variables, run commands, or build output, update that app's README in the same
change — and the root `README.md` table if the app's purpose or name changed.** Stale docs are
worse than missing docs: agents and humans both trust them. If you delete or rename something,
grep the `*.md` files for references to it before you finish.
