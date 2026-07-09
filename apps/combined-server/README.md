# Combined Server

The combined server runs the server-side parts of every app in one Spring Boot
process. It imports app controllers and services as modules; it does not launch
child Java processes.

## Endpoints

| App | Prefix |
| --- | --- |
| Auth | `/auth` |
| Klippy | `/klippy` |
| Jarvis | `/jarvis` |
| Keeboarder | `/keeboarder` |
| Secrets | `/secrets` |

`GET /` returns the registered app prefixes. Each prefix can be changed in the
combined env file with `CLIPPY_AUTH_ROUTE_PREFIX`,
`CLIPPY_SERVER_ROUTE_PREFIX`, `JARVIS_SERVER_ROUTE_PREFIX`, or
`KEEBOARDER_SERVER_ROUTE_PREFIX`. The secrets module uses
`SECRETS_ROUTE_PREFIX`.

## Build and run

### Docker Compose

```bash
docker compose -f apps/combined-server/docker-compose.yml up --build
```

### Standalone

```bash
mvn -pl apps/combined-server -am package
cp apps/combined-server/.env.example apps/combined-server/.env
java -jar apps/combined-server/target/combined-server-0.1.0-SNAPSHOT.jar
```

Set `COMBINED_SERVER_ENV_FILE` to use a configuration file outside
`apps/combined-server/.env`.

Jarvis storage uses the existing `PROXY_*`, AWS, or Azure environment settings.
Keeboarder's REST API and WebSocket runtime are both managed by the combined
Spring process. Configure the WebSocket listener with `WEBSOCKET_HOST` and
`WEBSOCKET_PORT`, and its client registry with `REDIS_HOST` and `REDIS_PORT`.
The REST endpoints require `Authorization: Bearer <token>` and
`X-Client-Id: <clientId>`; credentials are checked against
`CLIPPY_AUTH_BASE_URL`.

## Build and Test

```bash
mvn -pl apps/combined-server -am test
```

Integration tests use Testcontainers and require Docker.
