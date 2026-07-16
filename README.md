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
| Jarvis | `apps/jarvis` | Surveillance (bucket proxy, streaming, detection) |
| Keeboarder | `apps/keeboarder` | Keyboard/message relay |
| Klippy | `apps/klippy` | Clipboard history sync |
| Log analyzer | `apps/log-analyzer` | AI-assisted log triage feeding the alerting service |
| Secrets manager | `apps/secrets-manager` | Secret bundle/environment management |

## Packages

| Package | Directory | Purpose |
|---|---|---|
| Env | `packages/env` | Typed environment variable framework |
| Logger | `packages/logger` | Pluggable logging |
| Primitives | `packages/primitives` | Shared value types |
| Server bootstrap | `packages/server-bootstrap` | Shared Spring Boot wiring |
| Server parent | `packages/server-parent` | Parent POM for server modules |

## Build

Requires JDK 25+ and Maven 3.9+.

```bash
mvn package          # build everything
mvn test             # run all tests
mvn -pl <module> -am test   # build + test one module
```

Maven artifactIds match directory names (e.g. `apps/klippy/server` builds `klippy-server`),
so `mvn -pl :<artifactId>` and jar filenames line up with the tree. See `CLAUDE.md` for the
full module map and repo conventions.

See each app's README for run and configuration details.
