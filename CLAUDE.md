# Orwell monorepo — agent guide

Multi-module Java 25 / Maven monorepo: backend services under `apps/`, shared libraries under
`packages/`. Spring Boot 4.x for servers; plain Java for desktop clients.

## Build and test

```bash
mvn -q -DskipTests compile        # fast full-repo compile check
mvn test                          # all tests
mvn -pl :<artifactId> -am test    # one module + its deps (artifactIds match dir names)
scripts/dev-stack.sh              # local tmux stack (auth, klippy, secrets, proxy, keeboarder)
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
| `packages/server-parent` | `server-parent` (parent POM) | — |
| `packages/server-test-support` | `server-test-support` | `dev.orwell.testing` |

## Naming history (don't "fix" these)

- "Klippy" was historically spelled "Clippy". Java identifiers have been renamed to the current
  spelling (`KlippyServerApplication`, `KlippyAuthServerApplication`) — the compiler covers those,
  so they were safe to move. What survives is spelled `clippy` **only where the string is a
  contract**, and those are load-bearing:
  - `clippy-offline-clipboard.json` (+ its `-dead-letter` variant) and the file-locker socket
    `/tmp/clippy-offline-file-locker.sock` are client contracts baked into Java constants.
    Renaming either strands unsynced data on deployed clients; renaming the socket alone lets a
    new client and an old locker miss each other, so the lock stops excluding and two processes
    write the same file. They only move together, behind a read-both-write-new migration.
  - The audit-log stream `clippy-server` (`ClipboardEntryController`, and the app name in
    `KlippyServerApplication`) names the on-disk file `clippy-server.txt`. Nothing in-repo reads
    it but the test, so renaming it fails silently in whatever ships the logs.

  Everything user-facing (artifactIds, jars, docker images, docs, env vars) says "klippy".
- Jarvis's Java packages live under `dev.orwell.bucket.*`; klippy-server's under
  `dev.orwell.server`. Grep by package when tracing code, by artifactId when tracing builds.
- `apps/combined-server` was deleted deliberately. Do not recreate it; ignore references to it
  in old commits.
- `apps/klippy/devops/*.tf` keeps `clippy` in Terraform resource names, Azure identifiers, and
  the default database name — renaming those would destroy/recreate deployed infrastructure.
  `apps/klippy/.env.prod.example` intentionally matches that database name.
- **Local dev** Postgres now says `klippy` — compose service `db-klippy`, container
  `klippy-postgres`, the `*-klippy-pg-data` volumes, `POSTGRES_DB`/`POSTGRES_USER`, and the
  role/database `dev-stack.sh` creates — matching how the `auth` and `secrets` databases are
  already named after their app. Nothing migrates a pre-rename `clippy-pg-data` volume: it stays
  on disk as an orphan and you re-seed. `docker volume ls` still showing one is expected.
- The **deployed** database is a separate question from the local one, and still `clippy` — see
  the devops rule above. `apps/klippy/.env.prod.example` and `docker-compose.prod.yml` point at
  it, so they keep the old spelling on purpose; don't "finish" the rename by touching them.

## Repo conventions

- Server apps start via `AppServer` + `AppServerEnv`
  (`packages/server-bootstrap`, `dev.orwell.bootstrap.launch`). Shared auto-configs (health
  endpoint, 401 guard, logger, auth strategy) are registered in
  `packages/server-bootstrap/src/main/resources/META-INF/spring/…AutoConfiguration.imports` —
  if you move or rename any of those classes, update that file or the beans silently vanish.
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
