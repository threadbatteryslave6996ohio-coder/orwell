# Secrets Manager Server

Spring Boot service for secrets groups, environments, bundles, admins, and
accessors. The server validates bearer tokens against the auth server
configured by `AUTH_BASE_URL`.

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
- `AUTH_BASE_URL`

## Routes

The controller prefix is `${secrets.route-prefix:}`. By default the routes are
at the server root.

Admin routes live under `/admin` and require an admin bearer token plus
`X-Client-Id`:

- `POST /admin/admins`
- `GET /admin/admins`
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
