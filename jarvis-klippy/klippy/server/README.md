# Clippy Server

Spring Boot API that persists clipboard entries in PostgreSQL.

The server does not own client identities. It validates each clipboard request by calling the separate Clippy auth server.

## Requirements

Use the JDK, Maven, and Docker versions listed in the [root README](../README.md).
Docker is required for local PostgreSQL and Testcontainers-based tests.

## Start Locally

Start the app database on port `5432` and the auth database on port `5433` using your preferred local PostgreSQL setup.

Run the auth server from the repository root in one terminal:

```bash
cd /path/to/jarvis-klippy/klippy
mvn -pl ../auth/server spring-boot:run
```

Build and run this app server from the repository root in another terminal:

```bash
cd ~/Desktop/clippy
mvn -pl server -am package
java -jar server/target/clippy-server-0.1.0-SNAPSHOT-exec.jar
```

Or, if your shell is already in `~/Desktop/clippy/server`, invoke Maven from the root POM so it can include the auth client module:

```bash
cd ~/Desktop/clippy/server
mvn -f ../pom.xml -pl server -am package
java -jar target/clippy-server-0.1.0-SNAPSHOT-exec.jar
```

The example configuration runs the app server on `http://localhost:8080` and the auth server on `http://localhost:8081`.

## Configuration

The launcher loads configuration from a `.env` file in the current directory or any parent directory, applies nonblank shell values as overrides, and passes the resolved map into the server core. The core never fetches configuration itself.

All values are required. A local configuration is:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
SPRING_DATASOURCE_USERNAME=clippy
SPRING_DATASOURCE_PASSWORD=clippy
SERVER_PORT=8080
CLIPPY_AUTH_BASE_URL=http://localhost:8081
LOGGING_FILE_NAME=logs/clippy-server.log
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE=UTC
```

Set those values in `.env` before starting the server.
The core receives only the map resolved by the launcher; it does not read shell
exports or Spring command-line configuration independently.

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

The app server writes its normal Spring Boot logs to `LOGGING_FILE_NAME` and also writes a custom audit log file named `clippy-server.txt` in the configured directory.

Each successful `POST /clipboard` request records a line noting the `clientId`, generated entry id, and timestamp. Raw clipboard content is not written to the custom log.

The custom audit log is best-effort. If it cannot be written, the clipboard insert still succeeds.

## Tests

```bash
cd ~/Desktop/clippy
mvn -pl server -am test
```

Integration tests use Testcontainers PostgreSQL and require Docker.
