# Keeboarder Server

Java WebSocket server with Redis-backed client registry and a small HTTP API.
The server authenticates against the Clippy auth server configured through
`CLIPPY_AUTH_BASE_URL`.

## Requirements

- JDK 25+
- Maven 3.9+
- Redis
- Running Clippy auth server

## Build And Run

From the repository root:

```bash
mvn -pl apps/keeboarder/server -am package
java -jar apps/keeboarder/server/target/websocket-redis-server-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

The launcher loads `.env` from the current directory or any parent, then applies
nonblank shell overrides.

## Configuration

The main runtime settings are:

- `HTTP_HOST` and `HTTP_PORT` for the HTTP API
- `WEBSOCKET_HOST` and `WEBSOCKET_PORT` for the WebSocket listener
- `WEBSOCKET_CONTEXT_PATH` for the WebSocket path prefix
- `REDIS_HOST` and `REDIS_PORT` for client registry storage
- `CLIPPY_AUTH_BASE_URL` for token validation
- `KEEBOARDER_SERVER_ROUTE_PREFIX` for the HTTP API prefix

Defaults are `0.0.0.0:8080` for HTTP, `0.0.0.0:8025` for WebSocket, `/ws` for
the WebSocket context path, and `/api` for the HTTP route prefix.

## Endpoints

Connect to the WebSocket endpoint at `ws://localhost:8025/ws/chat` by default.
The register message must include `type=register`, `clientId`, `name`, and
`token`.

The HTTP API is rooted at `/api` by default and exposes:

- `GET /api/clients`
- `POST /api/send`

Both HTTP routes require `Authorization: Bearer <token>` and
`X-Client-Id: <clientId>`.

## Testing

```bash
mvn -pl apps/keeboarder/server -am test
```

In restricted sandboxes, tests that open local sockets may fail with
`java.net.SocketException: Operation not permitted`.
