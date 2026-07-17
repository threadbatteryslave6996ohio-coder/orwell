# Keeboarder Server

Java WebSocket server with Redis-backed client registry and a small HTTP API.
The server authenticates against the Klippy auth server configured through
`AUTH_BASE_URL`.

## Requirements

- JDK 25+
- Maven 3.9+
- Redis
- Running Klippy auth server

## Build And Run

From the repository root:

```bash
mvn -pl apps/keeboarder/server -am package
java -jar apps/keeboarder/server/target/keeboarder-server-0.1.0-SNAPSHOT-exec.jar
```

For Docker Compose with the same internal-Nginx pattern used in the other app
stacks:

```bash
docker compose -f apps/keeboarder/server/docker-compose.yml up --build
```

The launcher loads `.env` from the current directory or any parent, then applies
nonblank shell overrides.

## Configuration

The main runtime settings are:

- `SERVER_ADDRESS` and `SERVER_PORT` for the server (REST API and WebSocket share one port)
- `WEBSOCKET_CONTEXT_PATH` for the WebSocket path prefix
- `WEBSOCKET_ENABLED` to turn the WebSocket endpoint off entirely
- `REDIS_HOST` and `REDIS_PORT` for client registry storage
- `AUTH_BASE_URL` for token validation
- `KEEBOARDER_SERVER_ROUTE_PREFIX` for the HTTP API prefix

In the Compose setup here, the service listens internally on port `8025`, uses
`/keeboarder/api` for the HTTP route prefix, and `/keeboarder/ws` for the
WebSocket path prefix. Nginx publishes that stack on `localhost:8025` by
default.

## Endpoints

Connect to the WebSocket endpoint at
`ws://localhost:8025/keeboarder/ws/chat` by default in the Docker setup.
The register message must include `type=register`, `clientId`, `name`, and
`token`.

The HTTP API is rooted at `/keeboarder/api` in the Docker setup and exposes:

- `GET /keeboarder/api/clients`
- `POST /keeboarder/api/send`

Both HTTP routes require `Authorization: Bearer <token>` and
`X-Client-Id: <clientId>`.

## Testing

```bash
mvn -pl apps/keeboarder/server -am test
```

In restricted sandboxes, tests that open local sockets may fail with
`java.net.SocketException: Operation not permitted`.
