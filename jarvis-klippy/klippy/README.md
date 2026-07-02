# Clippy

Clippy records text clipboard changes from desktop and Android clients in a
Spring Boot server backed by PostgreSQL.

The Java code is a JDK 25 multi-module Maven project. The Android client is a
separate Kotlin/Gradle project under `clients/android`.

## Requirements

- JDK 25+ with `javac` on `PATH`
- Maven 3.9+
- Docker with Compose for local databases and integration tests
- Android Studio or a compatible Android toolchain for the Android client

Authentication is supplied by the parent repository's sibling `../auth`
directory. Clone this repository through the composition repository when you
need to build modules that depend on auth.

Verify the Java toolchain before troubleshooting Maven compilation errors:

```bash
java -version
javac -version
mvn -version
```

## Build and Test

Build every Java module from the repository root:

```bash
mvn package
```

Run tests without packaging:

```bash
mvn test
```

Server integration tests use Testcontainers and require Docker. To work on one
module while also building its dependencies, use:

```bash
mvn -pl <module> -am test
mvn -pl <module> -am package
```

The Android project is built separately from `clients/android`; see its README
for the available toolchain and tasks.

## Run Locally

The simplest complete deployment uses a Docker Compose profile:

```bash
# Auth server on 8081 and clipboard server on 8080
docker compose --profile separate up --build

# Auth and clipboard routes in one JVM on 8080
docker compose --profile combined up --build
```

Running Compose without a profile intentionally starts no services. The
separate deployment uses `http://localhost:8081` for auth and
`http://localhost:8080` for clipboard requests. The combined deployment uses
`http://localhost:8080/auth` and `http://localhost:8080/api` respectively.

For a host-based Linux development stack, configure the repository-root `.env`
and run:

```bash
./scripts/start-local-stack-tmux.sh
```

This starts the auth server, clipboard server, file-locker, Linux client, and
offline sync client in a `clippy` tmux session. Pass `--detached` to avoid
attaching immediately.

## Client Authentication

Create an identity once on the auth server:

```bash
curl -i http://localhost:8081/identities \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

Java clients can log in and refresh tokens automatically with this
repository-root `.env` configuration:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=dummy
CLIENT_SECRET=change-me-please
```

Alternatively, call `/login` and provide the returned value as `CLIENT_TOKEN`.
Shell environment variables override `.env` values.

## Documentation

- [Clipboard server](server/README.md)
- [Combined server](combined-server/README.md)
- [Shared server bootstrap](server-bootstrap/README.md)
- [Auth server](../auth/server/README.md)
- [Auth API contracts](../auth/api/README.md)
- [Auth client module](../auth/client/README.md)
- [macOS client](clients/mac/README.md)
- [Linux client](clients/linux/README.md)
- [Offline sync client](clients/offline-sync/README.md)
- [Offline file-locker](clients/file-locker/README.md)
- [Dummy client](clients/dummy/README.md)
- [Android client](clients/android/README.md)
- [Azure infrastructure](devops/README.md)

The server READMEs document the HTTP contracts, configuration, and deployment
details. Each client README covers only that client's setup and behavior.
