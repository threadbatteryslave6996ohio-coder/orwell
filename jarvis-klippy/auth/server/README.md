# Clippy Auth Server

Spring Boot service that owns Clippy client identities and issues login tokens.

The clipboard app server does not store client secrets or token records. It receives a bearer token on clipboard writes and calls this auth server's `/tokens/check` endpoint to verify that the token belongs to the request `clientId`.

## Responsibilities

- Create one identity per Clippy client.
- Store client secrets as salted PBKDF2 hashes.
- Issue random login tokens for valid client credentials.
- Store token hashes, not raw token values.
- Validate a token against a `clientId` for the app server.

## Requirements

Use the JDK, Maven, and Docker versions listed in the
[root README](../../README.md). Docker is required for local PostgreSQL and
Testcontainers-based tests.

## Start Locally

Start the auth database on port `5433` using your preferred local PostgreSQL setup, then run the server:

```bash
mvn -pl auth/server spring-boot:run
```

The example configuration runs the auth server on `http://localhost:8081`. Its local PostgreSQL container exposes database `auth` on host port `5433`.

To build a runnable jar instead:

```bash
cd ~/Desktop/clippy
mvn -pl auth/server -am package
java -jar auth/server/target/auth-server-0.1.0-SNAPSHOT-exec.jar
```

## Configuration

The launcher loads `.env` from the current directory or a parent, applies
nonblank shell values as overrides, and passes the result into the auth core.

All values are required. These values provide a local configuration.

| Environment variable | Example | Purpose |
| --- | --- | --- |
| `AUTH_SERVER_PORT` | `8081` | HTTP port for the auth server. |
| `AUTH_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/auth` | PostgreSQL JDBC URL. |
| `AUTH_DATASOURCE_USERNAME` | `auth` | Database username. |
| `AUTH_DATASOURCE_PASSWORD` | `auth` | Database password. |
| `AUTH_LOGGING_FILE_NAME` | `logs/auth-server.log` | File path for server logs. |
| `AUTH_JPA_HIBERNATE_DDL_AUTO` | `update` | Hibernate schema-management mode. |
| `AUTH_JPA_JDBC_TIME_ZONE` | `UTC` | Hibernate JDBC timezone. |

## Logging

Spring Boot writes normal logs to `AUTH_LOGGING_FILE_NAME`. A separate
`auth-server.txt` audit log in the same directory records startup, login, token
issuance, and token-check events. Raw secrets and bearer tokens are never
logged.

If you run the `CustomLogger` directly elsewhere, you can still redirect it with the JVM system property `custom.logger.dir`, for example:

```bash
java -Dcustom.logger.dir=/tmp/clippy-logs -jar auth/server/target/auth-server-0.1.0-SNAPSHOT-exec.jar
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

Example:

```bash
curl -i http://localhost:8081/identities \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

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

Use the returned token as `CLIENT_TOKEN` in Clippy client configuration. The raw token is shown only in this response. The database stores a SHA-256 hash of the token.

Invalid credentials return `401 Unauthorized`.

Example:

```bash
curl -s http://localhost:8081/login \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

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

Example:

```bash
curl -s http://localhost:8081/tokens/check \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","token":"generated-token"}'
```

## App Server Integration

Run this auth server before starting the main app server. Configure the app server with this auth base URL:

```text
CLIPPY_AUTH_BASE_URL=http://localhost:8081
```

If you are building a separate module that depends on the generated auth client directly, install that client locally first:

```bash
mvn -pl auth/client install
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
cd ~/Desktop/clippy
mvn -pl auth/server -am test
```

Tests that use PostgreSQL require a working Docker daemon when Testcontainers is involved.
