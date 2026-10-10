# HTTP Auth Server

Spring Boot service that owns client identities and issues login tokens. It is
the only service that stores client secrets or token records; every other
service delegates verification to it. See
[App Server Integration](#app-server-integration).

## Responsibilities

- Create one identity per client, inside a workspace, for a caller holding an admin token.
- Store client secrets as salted PBKDF2 hashes.
- Issue random login tokens for valid client credentials.
- Store token hashes, not raw token values.
- Validate a token against a `clientId` and workspace for the app server.
- Collect spent tokens, so `client_tokens` does not grow for the life of the deployment. See
  [Token retention](#token-retention).

## Workspaces

Every route lives under a workspace, which is the first path segment after the optional route
prefix: `/def/login`, `/admin/login`, `/team-a/tokens/check`. A `clientId` is unique *within* a
workspace, so `def/backup` and `team-a/backup` are two identities with two secrets.

Two names are built in:

| Workspace | What it is |
| --- | --- |
| `def` | Where identities live when nobody chose otherwise. Every client that predates workspaces lands here, because the auth client defaults to it. |
| `admin` | Identities that may provision. A token issued here is what `POST /{workspace}/identities` requires — in every workspace, including `admin` itself. |

Workspaces are not registered anywhere: an identity carries the name it was created in, so a
workspace exists exactly as long as an identity names it. Names must match `[a-z0-9]` with inner
hyphens, at most 64 characters, and `health` is rejected (it is the health route). Anything else
is `400`.

A token is only accepted in the workspace it was issued in — `/team-a/tokens/check` returns
`valid: false` for a token minted at `/def/login`, even for the same `clientId`.

### Bootstrapping the first admin

`POST /admin/identities` needs an admin token, and an empty database has none. Set
`AUTH_ADMIN_BOOTSTRAP_CLIENT_ID` and `AUTH_ADMIN_BOOTSTRAP_SECRET`; on startup the server creates
that identity in `admin` if it is absent. It never overwrites an existing one, so rotating the
secret afterwards is safe. Unset both once real admins exist.

### Migrating a database created before workspaces

The server migrates it for you at startup: `WorkspaceSchemaMigration` runs before Hibernate looks
at the schema, and on a `client_identities` table with no `workspace` column it adds the column,
backfills every existing row to `def`, drops the old single-column unique constraint on
`client_id`, and adds the composite one. It is skipped entirely on a database that has no such
table yet, and it fails startup rather than continuing half-migrated.

This is not something `ddl-auto: update` can do on its own, which is why the code exists: adding a
`NOT NULL` column to a table with rows is rejected — Hibernate logs that and carries on, and the
next query fails with *column workspace does not exist* — and Hibernate never drops the old unique
constraint, which would keep forbidding the same `clientId` in two workspaces.

Existing tokens keep working: they belong to identities that are now in `def`, which is where
their clients already look.

### Migrating a database created before tokens recorded their use

`ClientTokenSchemaMigration` does the same job for `client_tokens`, adding the `last_used_at`
column that [token retention](#token-retention) reads. It also runs before Hibernate, for the same
`NOT NULL`-on-a-populated-table reason, and is skipped on a database that has no such table.

Existing rows are backfilled to **the moment the migration runs**, not to `created_at`. Before this
column there was no record of use, so a token issued a year ago and used every minute since looks
identical to one abandoned the day it was minted. Backfilling to now gives every pre-existing token
a full idle period of grace, during which anything actually in use records a use and takes itself
out of the sweep. Because that grace is a stored timestamp, a server that restarts often neither
re-arms it nor defers collection forever.

## Requirements

Use the JDK, Maven, and Docker versions listed in the
[root README](../../README.md). Docker is required for local PostgreSQL and
Testcontainers-based tests.

## Start Locally

Start the shared database stack, then run the server. Both commands run from
the repository root:

```bash
docker compose -f docker-compose.storages.yml up -d db
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
| `AUTH_JPA_HIBERNATE_DDL_AUTO` | `update` | Hibernate schema-management mode. |
| `AUTH_JPA_JDBC_TIME_ZONE` | `UTC` | Hibernate JDBC timezone. |
| `AUTH_ADMIN_BOOTSTRAP_CLIENT_ID` | `ops-admin` | Optional. Seeds this identity in the `admin` workspace at startup if absent. Leave unset once real admins exist. |
| `AUTH_ADMIN_BOOTSTRAP_SECRET` | `change-me-please` | Optional, but required when the bootstrap client id is set, and at least 8 characters — a shorter one fails startup. |
| `AUTH_TOKEN_CLEANUP_ENABLED` | `true` | Optional. Whether to collect superseded, idle tokens. Defaults to `true`; set `false` to leave `client_tokens` append-only. |
| `AUTH_TOKEN_CLEANUP_INTERVAL_SECONDS` | `3600` | Optional. How often a sweep runs. Defaults to hourly. |
| `AUTH_TOKEN_CLEANUP_IDLE_SECONDS` | `86400` | Optional. How long a superseded token must go unused before it is collected. Defaults to one day. Must exceed 300 — the interval at which a token's use is recorded — or startup fails; below that the sweep would be reading a timestamp that has not caught up and would collect tokens in use. |
| `LOGGER` | `` | Optional. Which sinks the logger gets: `console`, `disk`, `loki`, `loki-with-fallback`, `both`. |
| `LOKI_URL` | `http://loki:3100` | Optional. Loki endpoint for log push. Console-only when unset. |
| `LOKI_TENANT_ID` | `orwell` | Optional. Loki tenant header. |

## Logging

Records go through the shared `dev.orwell.logging.Logger` bean. Login, token
issuance, and token-check events travel that path, as does the local-database
notice at startup. Raw secrets and bearer tokens are never logged.

A cleanup sweep logs only when it actually collected something — it runs on a
timer forever, and a steady stream of "deleted 0" would drown the records that
mean something.

`LOGGER` chooses the sinks; unset, it is console plus an async Loki push when
`LOKI_URL` is set and console only when it is not. Where losing an
authentication trail to a Loki outage is unacceptable, `loki-with-fallback`
keeps those records on disk instead. See
[`packages/logger/README.md`](../../../../packages/logger/README.md).

The server writes no log file of its own unless `LOGGER` asks for one.
`LOGGING_FILE_NAME` is accepted as a common key — it moves the log directory —
but is not set by the compose stack.

The service uses Hibernate `ddl-auto: update`, so it creates or updates the local schema on startup. The persistent tables are:

- `client_identities`
- `client_tokens`

## Token retention

A row went into `client_tokens` on every login and nothing ever took one out, so the table grew for
the life of the deployment and every token ever issued stayed valid. `TokenCleanupJob` sweeps on a
timer and deletes a token when **both** of these hold:

1. **It has been superseded** — the identity has logged in again since, so the row is a spent
   credential rather than the one its client is holding.
2. **It has not been used** within `AUTH_TOKEN_CLEANUP_IDLE_SECONDS` (a day by default).

An identity's newest token is never a candidate, however old and idle it is, so a client that logs
in once and then runs for a year keeps working. A client that logged in again while another process
was still using the old token keeps both, until that other process goes quiet for a day.

Being superseded does not have to be *recent*. Collecting only recently-replaced tokens would leave
everything replaced before the last sweep in the table forever, which is the growth this exists to
stop — so **the first sweep after upgrading clears the whole accumulated backlog**. It is batched,
each batch its own transaction, so that is many small deletes rather than one enormous one.

"Used" means the token authenticated something: a `/{workspace}/tokens/check` that returned
`valid: true`, or a provisioning call the admin guard accepted. A token that was presented and
*rejected* is not marked used — otherwise anyone holding a dead credential could keep it out of the
sweep by presenting it. The write is throttled to at most one per token every five minutes, because
`/tokens/check` is the busiest path in the stack and it must not become a row write per request; the
cost is that `last_used_at` can lag a real use by that much, which is noise against a one-day window.

So the guarantee is precisely *"never collects a token used within `AUTH_TOKEN_CLEANUP_IDLE_SECONDS`
minus five minutes"* — a day minus five minutes at the defaults, and nothing at all if the window
were set to five minutes or less. That is why a window at or below the throttle fails startup rather
than being accepted.

Set `AUTH_TOKEN_CLEANUP_ENABLED=false` to switch the sweep off and keep the table append-only.

This is collection, not expiry. A token still has no lifetime of its own — see
[Operational Notes](#operational-notes).

## API

All endpoints accept and return JSON, and all of them are scoped to a workspace.

### Create an Identity

Requires an admin credential: `X-Client-Id` plus a bearer token issued at `/admin/login`.

```http
POST /{workspace}/identities
Content-Type: application/json
X-Client-Id: ops-admin
Authorization: Bearer <admin-token>
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
  "workspace": "def",
  "clientId": "dummy",
  "createdAt": "2026-06-25T12:00:00Z"
}
```

Constraints:

- `clientId` is required and must be at most 128 characters; it is unique within the workspace.
- `secret` is required and must be 8 to 256 characters.
- Duplicate `clientId` values within one workspace return `409 Conflict`.
- A missing, non-admin, or unknown admin credential returns `401 Unauthorized` — one message for
  all three, since this is the endpoint that mints credentials.
- An unusable workspace name returns `400 Bad Request`, and is checked before the credential.

### Login

```http
POST /{workspace}/login
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
POST /{workspace}/tokens/check
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

The endpoint returns `valid: false` when the token is unknown, when the identity is inactive, when the token belongs to a different `clientId`, or when it was issued in a different workspace.

## App Server Integration

Run this auth server before starting the main app server. Configure the app server with this auth base URL:

```text
AUTH_BASE_URL=http://localhost:8081
AUTH_WORKSPACE=def
```

`AUTH_WORKSPACE` is optional everywhere and defaults to `def`, so an app that never sets it keeps
talking to the same identities it always did. Set it where a service's identities live somewhere
else.

If you are building a separate module that depends on the generated auth client directly, install that client locally first:

```bash
mvn -pl apps/auth/http-based/client -am install
```

Clipboard clients send the token from `/{workspace}/login` to the app server as a bearer token:

```http
Authorization: Bearer <client-token>
```

The app server passes the clipboard request `clientId` and bearer token to
`/{workspace}/tokens/check`. The token must have been issued for the same `clientId` in the same
workspace.

## Operational Notes

- There is no token expiry or revocation endpoint yet. Issuing a new token does not invalidate
  older tokens — a superseded one keeps working until [token retention](#token-retention) collects
  it, which needs it to have gone unused for a day first. So this bounds how long a spent
  credential survives; it is not a revocation mechanism, and it never shortens the life of a token
  that is in use.
- Identity records have an `active` flag in the database, but there is no HTTP endpoint for changing it yet.
- An admin cannot be demoted or moved between workspaces over HTTP: being an admin is living in the
  `admin` workspace, so removing one means deleting the identity in the database.
- Secrets are never returned by the API.
- Token values cannot be recovered from the database because only token hashes are stored.

## Tests

Run the auth server tests from the repository root:

```bash
mvn -pl apps/auth/http-based/server -am test
```

Tests that use PostgreSQL require a working Docker daemon when Testcontainers is involved.
