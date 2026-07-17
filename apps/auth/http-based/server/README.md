# HTTP Auth Server

Spring Boot service that owns client identities and issues login tokens.

The clipboard app server does not store client secrets or token records. It receives a bearer token on clipboard writes and calls this auth server's `/tokens/check` endpoint to verify that the token belongs to the request `clientId`.

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

Start the auth database on port `5433` using your preferred local PostgreSQL setup, then run the server:

```bash
mvn -pl apps/auth/http-based/server spring-boot:run
```

The example configuration runs the auth server on `http://localhost:8081`. Its local PostgreSQL container exposes database `auth` on host port `5433`.

To build a runnable jar instead:

```bash
mvn -pl apps/auth/http-based/server -am package
java -jar apps/auth/http-based/server/target/auth-http-server-0.1.0-SNAPSHOT-exec.jar
```

## Configuration

The launcher loads `.env` from the current directory or a parent, applies
nonblank shell values as overrides, and passes the result into the auth core.

All values are required. These values provide a local configuration.

| Environment variable | Example | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP port for the auth server. |
| `AUTH_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/auth` | PostgreSQL JDBC URL. |
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

Use the returned token as `CLIENT_TOKEN` in Klippy client configuration. The raw token is shown only in this response. The database stores a SHA-256 hash of the token.

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

## Known Issues

### Transaction boundaries & OSIV dependency

Controller methods (`login`, `createIdentity`, `checkToken`) lack `@Transactional`. Entity objects passed between repository calls (e.g., `ClientIdentity` from `findByClientId` into `new ClientToken`) rely on Spring Boot's Open Session In View being enabled by default. If OSIV is ever disabled, these calls will fail with detached-entity errors.

### TOCTOU race in identity creation

`createIdentity` checks `existsByClientId` before `save`, but a concurrent request can insert between the two calls. The `DataIntegrityViolationException` catch does recover, but the `existsByClientId` round-trip is redundant — the `UNIQUE` constraint alone is sufficient.

### `PBEKeySpec` secret not cleared from memory

`CredentialHasher.pbkdf2` never calls `PBEKeySpec.clearPassword()` in a `finally` block, leaving the secret char array in heap memory indefinitely.

### No token expiration

`ClientToken` has no `expiresAt` field. Issued tokens are valid forever. There is also no endpoint to revoke a single token or to deactivate an identity (the `active` column on `ClientIdentity` has no setter).

### No rate limiting on `/login`

The login endpoint performs 120k PBKDF2 iterations per request with no throttling, lockout, or CAPTCHA — enabling brute-force attacks and DoS amplification.

### Unbounded `token` input

`CheckTokenHttpRequest.token` has `@NotBlank` but no `@Size` constraint. An attacker can send multi-megabyte tokens, triggering expensive SHA-256 hashing and oversized database query parameters.

### Timing side-channel on inactive identities

The `isActive` filter is checked before PBKDF2 verification, so inactive accounts reject faster than active accounts with wrong passwords — allowing remote timing to infer account status.

### Data corruption not handled in `matches()`

`CredentialHasher.matches()` does not catch `NumberFormatException` or `IllegalArgumentException` from parsing a malformed stored hash, which would surface as a 500 error instead of a graceful 401.

### Test gaps

- `DataIntegrityViolationException` catch path is never exercised.
- No assertion that `tokens.save()` is never called on failed login.
- Real PBKDF2 (120k iterations) in unit tests makes them slow.
- No test for token-not-found in `checkToken`.
- `CredentialHasherTest` and `TokenGeneratorTest` hardcode literal values (`120000`, `43`) instead of referencing the source constants.

## Tests

Run the auth server tests from the repository root:

```bash
mvn -pl apps/auth/http-based/server -am test
```

Tests that use PostgreSQL require a working Docker daemon when Testcontainers is involved.
