# Secrets Manager Server

Spring Boot service for secrets groups, environments, and bundles. It holds no
identities of its own: it validates bearer tokens against **two** auth
deployments, and which one accepts a token is what grants the caller's role.

Secrets are key-value environment variables held in a **group**. A **bundle** is
a named collection of references to environments, so one bundle can gather
envs from several groups without copying their values. Deleting a group
cascades to its environments.

### Name collisions across groups

Env names are unique only *within* a group, so two groups can each define a
`DATABASE_URL`. Consumers flatten a bundle into a single map:
`EnvLoader.loadFromSecretsManager` (`packages/env/http`) does
`entries.put(entry.name(), entry.value())` over the bundle's environments, so a
bundle spanning both groups silently resolves to whichever entry came last in
the response. The accessor bundle payload does not carry the group id, so
neither the client nor a human reading the JSON can tell which one won.

Nothing in the schema or the API prevents this — if you build bundles that span
groups, name collisions are the failure mode to watch for.

## Requirements

- JDK 25+
- Maven 3.9+
- PostgreSQL
- Running auth server

## Build And Run

From the repository root:

```bash
mvn -pl apps/secrets-manager/server -am package
java -jar apps/secrets-manager/server/target/secrets-manager-server-0.1.0-SNAPSHOT-exec.jar
```

The launcher loads `.env` from the current directory or any parent, then applies
nonblank shell overrides.

## Configuration

The required runtime variables are:

- `SECRETS_DATASOURCE_URL`
- `SECRETS_DATASOURCE_USERNAME`
- `SECRETS_DATASOURCE_PASSWORD`
- `SERVER_PORT`
- `LOGGING_FILE_NAME`
- `SECRETS_JPA_HIBERNATE_DDL_AUTO`
- `SECRETS_JPA_JDBC_TIME_ZONE`
- `AUTH_BASE_URL` — the auth deployment holding ordinary clients (accessors)
- `SECRETS_ADMIN_AUTH_BASE_URL` — the auth deployment holding admins

`SECRETS_ROUTE_PREFIX` is optional and defaults to empty. It is published as
`secrets.route-prefix` and sets the controller prefix described below.

## Authentication And Authorization

Roles are resolved per request, not stored as claims. **The role is the
deployment**: an identity registered in the admin auth server is an admin, one
registered in the client auth server is an accessor.

1. The request carries `Authorization: Bearer <token>` and `X-Client-Id`. A
   missing client id or missing bearer token is `401 Unauthorized`.
2. The token is checked against the deployment the route requires —
   `SECRETS_ADMIN_AUTH_BASE_URL` for `/admin` routes, `AUTH_BASE_URL` for
   accessor routes. If it is accepted, the request proceeds.
3. If it is rejected, the *other* deployment is checked. Accepted there means a
   real identity in the wrong role: `403 Forbidden`. Rejected by both means
   `401 Unauthorized`.

There is no admin or accessor table, and no auth material — passwords, tokens,
or client secrets — is stored in this service's database. Granting or revoking
either role is done in the corresponding auth deployment via its `/identities`
API, not here.

The two beans are wired in `auth/SecretsAuthConfiguration.java` as distinct
wrapper types (`AdminAuth`, `ClientAuth`) rather than two `AuthenticationStrategy`
beans, so `server-bootstrap`'s by-type injection of the shared request-scoped
`AuthenticationContext` stays unambiguous and neither bean needs a qualifier.
Controllers read the credentials off the request rather than from that shared
context, which only ever speaks to the client deployment.

## Routes

The controller prefix is `${secrets.route-prefix:}`. By default the routes are
at the server root.

Admin routes live under `/admin` and require an admin bearer token plus
`X-Client-Id`:

- `POST /admin/groups`
- `GET /admin/groups`
- `GET /admin/groups/{id}`
- `PUT /admin/groups/{id}`
- `DELETE /admin/groups/{id}`
- `POST /admin/groups/{groupId}/envs`
- `GET /admin/groups/{groupId}/envs`
- `GET /admin/groups/{groupId}/envs/{envId}`
- `PUT /admin/groups/{groupId}/envs/{envId}`
- `DELETE /admin/groups/{groupId}/envs/{envId}`
- `POST /admin/bundles`
- `GET /admin/bundles`
- `GET /admin/bundles/{id}`
- `PUT /admin/bundles/{id}`
- `PUT /admin/bundles/{id}/envs`
- `DELETE /admin/bundles/{id}`

Accessor routes require an accessor bearer token plus `X-Client-Id`:

- `GET /groups`
- `GET /groups/{groupId}/envs`
- `GET /groups/{groupId}/envs/{envId}`
- `GET /groups/{groupId}/envs/by-name/{envName}`
- `GET /bundles`
- `GET /bundles/{id}`

## Tests

```bash
mvn -pl apps/secrets-manager/server -am test
```
