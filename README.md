# orwell

Multi-module monorepo for a suite of backend services and desktop clients.

## Apps

| App | Directory | Purpose |
|---|---|---|
| Auth | `apps/auth` | Client identity and token management |
| Clippy | `apps/klippy` | Clipboard history sync |
| Jarvis | `apps/jarvis` | Surveillance (bucket proxy, streaming, alerting) |
| Keeboarder | `apps/keeboarder` | Keyboard/message relay |

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

See each app's README for run and configuration details.
