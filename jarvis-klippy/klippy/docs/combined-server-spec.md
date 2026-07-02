# Combined Server Specification

## Deployment modes

Implement two deployment modes:

1. `standalone`
2. `combined`

### Standalone

- run `auth/server` as its own Spring Boot app
- run `server` as its own Spring Boot app
- keep the current HTTP communication from clipboard server to auth server

### Combined

- add a new Spring Boot app named `combined-server`
- run auth routes and clipboard routes in one JVM
- keep clipboard-to-auth validation on HTTP for the first implementation

## Module layout

Create or reshape modules so the code can be assembled into both standalone and combined deployments.

Target structure:

```text
auth/
  core/
  server/

server/
  core/
  standalone/

combined-server/
```

Equivalent naming is acceptable if the boundaries remain the same.

## Auth module

The auth reusable module must contain:

- controllers
- entities
- repositories
- credential hashing
- token generation
- auth Spring configuration

Add a reusable auth configuration class:

`dev.clippy.auth.AuthModuleConfiguration`

This configuration must support:

- standalone auth deployment
- combined deployment

## Clipboard module

The clipboard reusable module must contain:

- controllers
- entities
- repositories
- clipboard Spring configuration
- existing HTTP-based auth client wiring

Add a reusable clipboard configuration class:

`dev.clippy.server.ClipboardModuleConfiguration`

This configuration must support:

- standalone clipboard deployment
- combined deployment

## Standalone launchers

Keep the current standalone launchers and reduce them to bootstrapping only.

Classes:

- `dev.clippy.auth.ClippyAuthServerApplication`
- `dev.clippy.server.ClippyServerApplication`

These launchers should:

- load env values
- apply Spring properties
- configure logging
- import the reusable module configuration
- start Spring Boot

## Combined launcher

Add:

- Maven module: `combined-server`
- main class: `dev.clippy.combined.CombinedServerApplication`

The combined launcher must:

- start one embedded server
- import auth module configuration
- import clipboard module configuration
- configure the auth datasource
- configure the clipboard datasource
- configure one entity manager factory per datasource
- configure one transaction manager per datasource
- expose one HTTP port
- read a combined-specific env file

## Routing

Add configurable route prefixes.

### Auth routes

Combined mode must expose:

- `/auth/identities`
- `/auth/login`
- `/auth/tokens/check`

Standalone auth must keep:

- `/identities`
- `/login`
- `/tokens/check`

### Clipboard routes

Combined mode must expose clipboard routes under one prefix.

Use:

- `/api/clipboard`

Standalone clipboard must keep:

- `/clipboard`

### Required properties

Add:

- `clippy.auth.route-prefix`
- `clippy.server.route-prefix`

Values:

- standalone auth: empty
- standalone clipboard: empty
- combined auth: `/auth`
- combined clipboard: `/api`

## Auth communication

For the first combined-server implementation, use HTTP for clipboard-to-auth validation.

Required behavior:

- standalone clipboard server uses HTTP to call auth server
- combined clipboard module also uses HTTP to call auth routes exposed by the same combined process

Do not add direct service injection for auth validation in this implementation.

## Datasources

Keep two distinct PostgreSQL databases on the same PostgreSQL server.

### Auth database

it should read from the env its important that the two different subapss connect ot hteir respective db in the same db server

### Combined server wiring

The combined server must define:

- `authDataSource`
- `clipboardDataSource`
- `authEntityManagerFactory`
- `clipboardEntityManagerFactory`
- `authTransactionManager`
- `clipboardTransactionManager`

## Environment configuration

The combined deployment must use its own env file.

Allowed locations:

- `combined-server/.env`

The combined launcher must explicitly load the intended env file.

### Minimum combined env

```dotenv
COMBINED_SERVER_PORT=8080

AUTH_DATASOURCE_URL=jdbc:postgresql://dbhost:5432/clippy_auth
AUTH_DATASOURCE_USERNAME=clippy_auth
AUTH_DATASOURCE_PASSWORD=replace-me

SPRING_DATASOURCE_URL=jdbc:postgresql://dbhost:5432/clippy_app
SPRING_DATASOURCE_USERNAME=clippy_app
SPRING_DATASOURCE_PASSWORD=replace-me

LOGGING_FILE_NAME=logs/clippy-server.log

CLIPPY_AUTH_BASE_URL=http://localhost:8080/auth
CLIPPY_AUTH_ROUTE_PREFIX=/auth
CLIPPY_SERVER_ROUTE_PREFIX=/api
```

## Package layout

### Auth package root

Use:

`dev.clippy.auth`

### Clipboard package root

Use:

`dev.clippy.server`

### Combined package root

Use:

`dev.clippy.combined`

## Implementation phases

### Phase 1

- add configurable route prefixes to auth and clipboard controllers
- keep existing HTTP auth client behavior in clipboard server

### Phase 2

- extract reusable auth module configuration
- extract reusable clipboard module configuration
- keep standalone launchers as thin bootstrappers

### Phase 3

- add `combined-server` Maven module
- add combined main class
- wire two datasources
- wire two JPA stacks
- point clipboard auth HTTP client at combined auth routes

### Phase 4

- add env loading for the combined deployment
- add docs for starting standalone and combined modes
- add tests for combined startup and routing

## Required tests

### Standalone regression

- auth server tests pass
- clipboard server tests pass

### Combined tests

- combined server starts
- `/auth/identities` works
- `/auth/login` works
- `/auth/tokens/check` works
- `/api/clipboard` accepts valid requests
- `/api/clipboard` rejects invalid tokens
- auth data persists only in the auth database
- clipboard data persists only in the clipboard database

### Combined integration setup

Use either:

- two PostgreSQL Testcontainers
- one PostgreSQL container with two created databases

## Future work

The future direct-service auth integration is tracked in:

- `TODO.md`
