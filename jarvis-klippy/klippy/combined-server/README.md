# Combined Server

Spring Boot deployment target that runs the auth routes and clipboard routes in one JVM.

## Start Locally

Copy [`.env.example`](.env.example) to `combined-server/.env`, or point `CLIPPY_ENV_FILE` at another file. The launcher selects only that file, applies nonblank shell values as overrides, and fails fast if the file is missing:

```dotenv
COMBINED_SERVER_PORT=8080

AUTH_DATASOURCE_URL=jdbc:postgresql://localhost:5433/auth
AUTH_DATASOURCE_USERNAME=auth
AUTH_DATASOURCE_PASSWORD=auth

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
SPRING_DATASOURCE_USERNAME=clippy
SPRING_DATASOURCE_PASSWORD=clippy

AUTH_JPA_HIBERNATE_DDL_AUTO=update
CLIPBOARD_JPA_HIBERNATE_DDL_AUTO=update
JPA_JDBC_TIME_ZONE=UTC

CLIPPY_AUTH_BASE_URL=http://localhost:8080/auth
CLIPPY_AUTH_ROUTE_PREFIX=/auth
CLIPPY_SERVER_ROUTE_PREFIX=/api

LOGGING_FILE_NAME=logs/clippy-combined-server.log
```

Build and run from the repository root:

```bash
mvn -pl combined-server -am package
cd combined-server
java -jar target/clippy-combined-server-0.1.0-SNAPSHOT.jar
```

Every value shown above is required. The launcher parses the selected file and
passes the resolved values into the core as Spring application properties. The
core does not read files, process environment variables, or accept Spring
command-line configuration independently.

The combined server exposes:

- `POST /auth/identities`
- `POST /auth/login`
- `POST /auth/tokens/check`
- `POST /api/clipboard`
- `GET /api/clipboard`

The clipboard module continues to validate tokens over HTTP. In combined mode, the auth client points at the combined server's own `/auth` routes.
