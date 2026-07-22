# HTTP Auth Server

Spring Boot service that owns client identities and issues login tokens. It is
the only service that stores client secrets or token records; every other
service delegates verification to it. See
[App Server Integration](#app-server-integration).

## Responsibilities

- Create one identity per Klippy client.
- Store client secrets as salted PBKDF2 hashes.
- Issue random login tokens for valid client credentials.
- Store token hashes, not raw token values.
- Validate a token against a `clientId` for the app server.

## Requirements

Use the JDK, Maven, and Docker versions listed in the
[root README](../../README.md). Docker is required for local PostgreSQL and
Testcontainers-based tests.

## Start Locally

Start the shared database stack, then run the server. Both commands run from
the repository root:

```bash
docker compose -f docker-compose.all-services.yml up -d db
mvn -pl apps/auth/http-based/server spring-boot:run
```

The example configuration runs the auth server on `http://localhost:8081`. The shared PostgreSQL instance exposes database `auth` on host port `5432`.

To build a runnable jar instead:

```bash
mvn -pl apps/auth/http-based/server -am package
java -jar apps/auth/http-based/server/target/auth-http-server-0.1.0-SNAPSHOT-exec.jar
```

## Configuration

The launcher loads `.env` from the current directory or a parent, applies
nonblank shell values as overrides, and passes the result into the auth core.

All values are required unless marked optional. These values provide a local configuration.

| Environment variable | Example | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP port for the auth server. |
| `AUTH_ROUTE_PREFIX` | `/auth` | Optional. Path prefix the auth routes are served under, published as `orwell.auth.route-prefix`. Defaults to empty, serving routes at the root; set it when the server sits behind a shared reverse proxy. `/health` is never prefixed. |
| `AUTH_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/auth` | PostgreSQL JDBC URL. |
| `AUTH_DATASOURCE_USERNAME` | `auth` | Database username. |
| `AUTH_DATASOURCE_PASSWORD` | `auth` | Database password. |
| `LOGGING_FILE_NAME` | `logs/auth-server.log` | File path for server logs. |
| `AUTH_JPA_HIBERNATE_DDL_AUTO` | `update` | Hibernate schema-management mode. |
| `AUTH_JPA_JDBC_TIME_ZONE` | `UTC` | Hibernate JDBC timezone. |

## Logging

Spring Boot writes normal logs to `LOGGING_FILE_NAME`. A separate
`auth-server.txt` audit log in the same directory records startup, login, token
issuance, and token-check events. Raw secrets and bearer tokens are never
logged.

If you run the `CustomLogger` directly elsewhere, you can still redirect it with the JVM system property `custom.logger.dir`, for example:

```bash
java -Dcustom.logger.dir=/tmp/auth-logs -jar apps/auth/http-based/server/target/auth-http-server-0.1.0-SNAPSHOT-exec.jar
```

For Azure, point `AUTH_DATASOURCE_URL` at the `auth` database on the deployed PostgreSQL server.

The service uses Hibernate `ddl-auto: update`, so it creates or updates the local schema on startup. The persistent tables are:

- `client_identities`
- `client_tokens`

## API

All endpoints accept and return JSON.

### Create an Identity

```http
POST /identities
Content-Type: application/json
```

```json
{
  "clientId": "dummy",
  "secret": "change-me-please"
}
```

Response:

```http
201 Created
```

```json
{
  "clientId": "dummy",
  "createdAt": "2026-06-25T12:00:00Z"
}
```

Constraints:

- `clientId` is required and must be at most 128 characters.
- `secret` is required and must be 8 to 256 characters.
- Duplicate `clientId` values return `409 Conflict`.

### Login

```http
POST /login
Content-Type: application/json
```

```json
{
  "clientId": "dummy",
  "secret": "change-me-please"
}
```

Response:

```json
{
  "clientId": "dummy",
  "token": "generated-token"
}
```

Use the returned token as `CLIENT_TOKEN` in Klippy client configuration. The raw token is shown only in this response. The database stores a SHA-256 hash of the token.

Invalid credentials return `401 Unauthorized`.

### Check a Token

This endpoint is primarily for the app server.

```http
POST /tokens/check
Content-Type: application/json
```

```json
{
  "clientId": "dummy",
  "token": "generated-token"
}
```

Response:

```json
{
  "valid": true,
  "clientId": "dummy"
}
```

The endpoint returns `valid: false` when the token is unknown, when the identity is inactive, or when the token belongs to a different `clientId`.

## App Server Integration

Run this auth server before starting the main app server. Configure the app server with this auth base URL:

```text
AUTH_BASE_URL=http://localhost:8081
```

If you are building a separate module that depends on the generated auth client directly, install that client locally first:

```bash
mvn -pl apps/auth/http-based/client -am install
```

Clipboard clients send the token from `/login` to the app server as a bearer token:

```http
Authorization: Bearer <client-token>
```

The app server passes the clipboard request `clientId` and bearer token to `/tokens/check`. The token must have been issued for the same `clientId`.

## Operational Notes

- There is no token expiry or revocation endpoint yet. Issuing a new token does not invalidate older tokens.
- Identity records have an `active` flag in the database, but there is no HTTP endpoint for changing it yet.
- Secrets are never returned by the API.
- Token values cannot be recovered from the database because only token hashes are stored.

## Tests

Run the auth server tests from the repository root:

```bash
mvn -pl apps/auth/http-based/server -am test
```

Tests that use PostgreSQL require a working Docker daemon when Testcontainers is involved.
