# Klippy Server

Spring Boot API that persists clipboard entries in PostgreSQL.

The server does not own client identities. It validates each clipboard request
by calling the separate Klippy auth server.

## Requirements

Use the JDK, Maven, and Docker versions listed in the [root README](../../../README.md).
Docker is required for local PostgreSQL and Testcontainers-based tests.

## Start Locally

Start the shared database stack; it serves the `klippy`, `auth` and `secrets` databases from one PostgreSQL on port `5432`:

```bash
docker compose -f ../../../docker-compose.all-services.yml up -d db
```

Run the auth server from the repository root in one terminal:

```bash
mvn -pl apps/auth/http-based/server spring-boot:run
```

Build and run this app server from the repository root in another terminal:

```bash
mvn -pl apps/klippy/server -am package
java -jar apps/klippy/server/target/klippy-server-0.1.0-SNAPSHOT-exec.jar
```

The example configuration runs the app server on `http://localhost:8080` and the auth server on `http://localhost:8081`.

## Configuration

The launcher loads configuration from a `.env` file in the current directory or any parent directory, applies nonblank shell values as overrides, and passes the resolved map into the server core. The core never fetches configuration itself.

A local configuration is:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/klippy
SPRING_DATASOURCE_USERNAME=klippy
SPRING_DATASOURCE_PASSWORD=klippy
SERVER_ADDRESS=0.0.0.0
SERVER_PORT=8080
AUTH_BASE_URL=http://localhost:8081
LOGGING_FILE_NAME=logs/klippy-server.log
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE=UTC
```

Every value above is required. Two further keys come from `AppServerEnv` and are
optional:

```text
LOKI_URL=http://localhost:3100
LOKI_TENANT_ID=orwell
```

`LOKI_URL` enables the Loki log sink described below; `LOKI_TENANT_ID` is sent as
the `X-Scope-OrgID` header when Loki is multi-tenant. Without `LOKI_URL` the
server logs to the console only.

## Endpoint

```http
POST /clipboard
Content-Type: application/json
Authorization: Bearer <client-token>
```

```json
{
  "clientId": "android-pixel-8",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```

`timestamp` is optional. When it is omitted, the server stores the current time.

The bearer token must have been issued by the auth server for the same `clientId` in the JSON body.

Query all clipboard entries for one client in an inclusive timeframe:

```http
GET /clipboard?clientId=ubuntu-gnome&from=2026-06-23T12%3A00%3A00Z&to=2026-06-23T13%3A00%3A00Z
Authorization: Bearer <client-token>
```

The response is ordered by timestamp and id and includes each entry's `id`,
`clientId`, `content`, and `timestamp`. The bearer token must belong to the
requested client. A timeframe with `from` later than `to` returns `400`.

Optional `limit` (1-1000), `afterTimestamp`, and `afterId` parameters provide
cursor pagination. Both cursor parameters must be supplied together. Clipboard
content is limited to 1,000,000 characters.

## Logging

The server logs through the injected `dev.orwell.logging.Logger`. Its bean comes from `LoggerConfiguration` in `packages/server-bootstrap` and is a `FailSafeLogger` wrapping a `CompositeLogger` of `ConsoleLogger` and `LokiLogger`: records go to stdout and are pushed asynchronously to Loki. The server writes no application log file of its own.

Set `LOKI_URL` to enable the Loki sink, and `LOKI_TENANT_ID` when the Loki instance is multi-tenant. If `LOKI_URL` is unset the bean falls back to console-only logging and warns at startup.

Each successful `POST /clipboard` request records the `clientId`, generated entry id, and timestamp as structured metadata. Raw clipboard content is never logged.

Logging is best-effort: `FailSafeLogger` absorbs sink failures, and `LokiLogger` batches from a bounded queue on its own thread, so neither a failing nor a slow sink can turn a clipboard insert into an error.

## Tests

```bash
mvn -pl apps/klippy/server -am test
```

Integration tests use Testcontainers PostgreSQL and require Docker.
