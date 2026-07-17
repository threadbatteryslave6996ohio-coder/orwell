# Secrets Manager (`apps/secrets-manager`)

## Overview

A Spring Boot service that manages secret environment variables (key-value pairs)
organized into **groups** and **bundles**, with role-based access via the existing
Orwell auth service.

---

## Project Structure

```
apps/secrets-manager/
├── pom.xml                              # Parent POM (packaging: pom)
├── server/
│   ├── pom.xml                          # secrets-manager-server (Spring Boot app)
│   └── src/main/java/dev/orwell/secrets/
└── client/
    ├── pom.xml                          # secrets-manager-client (Java library)
    └── src/main/java/dev/orwell/secrets/client/
        ├── SecretsManagerApplication.java
        ├── SecretsManagerLauncher.java
        ├── SecretsManagerEnvs.java
        ├── config/
        │   ├── AuthClientConfiguration.java
        │   └── SecretsServerProperties.java
        ├── model/
        │   ├── AdminIdentity.java
        │   ├── AccessorIdentity.java
        │   ├── SecretGroup.java
        │   ├── SecretEnvironment.java
        │   ├── SecretBundle.java
        │   └── SecretBundleEntry.java
        ├── repository/
        │   ├── AdminIdentityRepository.java
        │   ├── AccessorIdentityRepository.java
        │   ├── SecretGroupRepository.java
        │   ├── SecretEnvironmentRepository.java
        │   ├── SecretBundleRepository.java
        │   └── SecretBundleEntryRepository.java
├── controller/
│   ├── admin/
│   │   ├── SecretsAdminController.java
│   │   ├── CreateAdminRequest.java
│   │   ├── AdminResponse.java
│   │   ├── CreateGroupRequest.java
│   │   ├── UpdateGroupRequest.java
│   │   ├── GroupResponse.java
│   │   ├── GroupDetailResponse.java
│   │   ├── CreateEnvironmentRequest.java
│   │   ├── UpdateEnvironmentRequest.java
│   │   ├── EnvironmentResponse.java
│   │   ├── CreateBundleRequest.java
│   │   ├── UpdateBundleRequest.java
│   │   ├── SetBundleEnvsRequest.java
│   │   ├── BundleResponse.java
│   │   └── BundleDetailResponse.java
│   └── accessor/
│       ├── SecretsAccessorController.java
│       ├── AccessorGroupResponse.java
│       ├── AccessorEnvironmentResponse.java
│       ├── AccessorBundleResponse.java
│       └── AccessorBundleDetailResponse.java
        └── service/
            ├── SecretsAdminService.java
            ├── SecretsAccessorService.java
            └── AuthValidator.java
```

---

## Database Schema

| Table | Columns | Notes |
|---|---|---|
| `admins` | `id` (PK, BIGSERIAL), `name` (VARCHAR 128, UNIQUE), `created_at` (TIMESTAMP) | `name` = clientId from auth service |
| `accessors` | `id` (PK, BIGSERIAL), `name` (VARCHAR 128, UNIQUE), `created_at` (TIMESTAMP) | `name` = clientId from auth service |
| `secret_groups` | `id` (PK, BIGSERIAL), `name` (VARCHAR 255, UNIQUE), `description` (TEXT), `created_at` (TIMESTAMP), `created_by` (VARCHAR 128) | Logical group of envs |
| `secret_environments` | `id` (PK, BIGSERIAL), `group_id` (FK → secret_groups.id), `name` (VARCHAR 255), `value` (TEXT), `created_at` (TIMESTAMP), `updated_at` (TIMESTAMP) | UNIQUE `(group_id, name)` |
| `secret_bundles` | `id` (PK, BIGSERIAL), `name` (VARCHAR 255, UNIQUE), `description` (TEXT), `created_at` (TIMESTAMP), `created_by` (VARCHAR 128) | Named collection of env references |
| `secret_bundle_entries` | `id` (PK, BIGSERIAL), `bundle_id` (FK → secret_bundles.id, CASCADE DELETE), `env_id` (FK → secret_environments.id) | UNIQUE `(bundle_id, env_id)` |

---

## Authentication & Authorization

1. Request arrives with `Authorization: Bearer <token>`
2. Extract `clientId` from request (body param or path — all requests carry it)
3. Call `AuthenticationStrategy.isTokenValidForClient(clientId, token)` — delegates to auth service
4. Look up `clientId` in `admins` table → **admin** role
5. Look up `clientId` in `accessors` table → **accessor** role
6. Neither → **403 Forbidden**

No auth info (passwords, tokens, secrets) is stored in this service's DB.

---

## Route Prefix

- System property: `secrets.route-prefix` (default: empty string)
- Standalone: no prefix
- Combined server: `/secrets`

---

## API Endpoints

### Admin Endpoints (admin role required)

Admin controller is mapped at `{prefix}/admin` (to avoid route conflicts with the accessor controller).

| Method | Path | Description |
|---|---|---|
| `POST` | `{prefix}/admin/admins` | Add an admin |
| `GET` | `{prefix}/admin/admins` | List all admins |
| | | |
| `POST` | `{prefix}/admin/groups` | Create a secret group |
| `GET` | `{prefix}/admin/groups` | List all groups |
| `GET` | `{prefix}/admin/groups/{id}` | Get group details |
| `PUT` | `{prefix}/admin/groups/{id}` | Update group name/description |
| `DELETE` | `{prefix}/admin/groups/{id}` | Delete group + all its envs (cascade) |
| | | |
| `POST` | `{prefix}/admin/groups/{groupId}/envs` | Create an env in a group |
| `GET` | `{prefix}/admin/groups/{groupId}/envs` | List all envs in a group |
| `GET` | `{prefix}/admin/groups/{groupId}/envs/{envId}` | Get a specific env |
| `PUT` | `{prefix}/admin/groups/{groupId}/envs/{envId}` | Update env name and/or value |
| `DELETE` | `{prefix}/admin/groups/{groupId}/envs/{envId}` | Delete an env |
| | | |
| `POST` | `{prefix}/admin/bundles` | Create bundle |
| `GET` | `{prefix}/admin/bundles` | List all bundles |
| `GET` | `{prefix}/admin/bundles/{id}` | Get bundle with resolved env values |
| `PUT` | `{prefix}/admin/bundles/{id}` | Update bundle name/description |
| `PUT` | `{prefix}/admin/bundles/{id}/envs` | Replace env references in bundle |
| `DELETE` | `{prefix}/admin/bundles/{id}` | Delete bundle |

### Accessor Endpoints (accessor role required)

| Method | Path | Description |
|---|---|---|
| `GET` | `{prefix}/groups` | List groups (id + name only) |
| `GET` | `{prefix}/groups/{groupId}/envs` | List all envs (name + value) in a group |
| `GET` | `{prefix}/groups/{groupId}/envs/{envId}` | Get specific env by ID |
| `GET` | `{prefix}/groups/{groupId}/envs/by-name/{envName}` | Get env by name within group |
| `GET` | `{prefix}/bundles` | List bundles (id + name only) |
| `GET` | `{prefix}/bundles/{id}` | Get bundle with resolved env values |

---

## Integration with Combined Server

Follow the exact same patterns as auth and klippy modules:

1. **`CombinedSecretsModuleConfiguration.java`** — `@ComponentScan` over the `dev.orwell.secrets` root package, excluding `SecretsManagerApplication.class`
2. **`CombinedSecretsDatabaseConfiguration.java`** — Multi-datasource JPA config with its own `DataSource`, `EntityManagerFactory`, `TransactionManager`, using `SecretsManagerEnvs` environment vars
3. **`CombinedEnvs` additions:**
   - `SECRETS_ROUTE_PREFIX` → `secrets.route-prefix`
   - `SECRETS_DATASOURCE_URL / USERNAME / PASSWORD` → `secrets.datasource.*`
   - `SECRETS_JPA_HIBERNATE_DDL_AUTO` → `secrets.jpa.hibernate.ddl-auto`
4. **`CombinedServerApplication` update:** import `CombinedSecretsModuleConfiguration` and `CombinedSecretsDatabaseConfiguration`

---

## Java Client (`secrets-manager-client`)

A lightweight HTTP client for accessing secrets manager data (groups, envs, bundles).

### Auth Providers

| Provider | Constructor | Description |
|---|---|---|
| `TokenAuthProvider` | `(token, clientId)` | Uses a pre-obtained bearer token |
| `PasswordAuthProvider` | `(authServerUrl, clientId, secret)` | Calls auth server `/login` to obtain a token (via `HttpAuthenticationStrategy.login()`) |

### Usage

```java
var auth = new TokenAuthProvider("my-token", "my-client-id");
var client = new SecretsManagerClient("http://localhost:8085", auth);

List<Group> groups = client.listGroups();
List<Environment> envs = client.listEnvironments(groups.get(0).id());
Environment env = client.getEnvironmentByGroupAndName(groupId, "DB_URL");
List<Bundle> bundles = client.listBundles();
BundleDetail detail = client.getBundle(bundles.get(0).id());
```

### Dependencies

- `auth-http-client` (for `HttpAuthenticationStrategy.login()` used by `PasswordAuthProvider`)
- `jackson-databind`

---

## Dependencies (server/pom.xml)

Same set used by `klippy-server` and `auth-http-server`:

- `server-bootstrap`
- `auth-core` (for `AuthenticationStrategy` interface)
- `auth-http-client` (for `HttpAuthenticationStrategy`)
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `spring-boot-starter-web`
- `postgresql` (runtime)
- `spring-boot-starter-test` (test)
- `testcontainers:junit-jupiter` (test)
- `testcontainers:postgresql` (test)

---

## Environment Variables (standalone)

| Variable | Maps to |
|---|---|
| `SECRETS_DATASOURCE_URL` | `spring.datasource.url` |
| `SECRETS_DATASOURCE_USERNAME` | `spring.datasource.username` |
| `SECRETS_DATASOURCE_PASSWORD` | `spring.datasource.password` |
| `SERVER_PORT` | `server.port` |
| `LOGGING_FILE_NAME` | `logging.file.name` |
| `SECRETS_JPA_HIBERNATE_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` |
| `SECRETS_JPA_JDBC_TIME_ZONE` | `spring.jpa.properties.hibernate.jdbc.time_zone` |
| `AUTH_BASE_URL` | `orwell.auth.base-url` |
