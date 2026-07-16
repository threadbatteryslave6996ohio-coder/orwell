# Klippy

Klippy records text clipboard changes from desktop and Android clients in a
Spring Boot server backed by PostgreSQL.

The Java code is a JDK 25 multi-module Maven project. The Android client is a
separate Kotlin/Gradle project under `apps/klippy/clients/android`.

## Requirements

- JDK 25+ with `javac` on `PATH`
- Maven 3.9+
- Docker with Compose for local databases and integration tests
- Android Studio or a compatible Android toolchain for the Android client

Authentication is supplied by the sibling `apps/auth` directory in this repository.

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

The Android project is built separately from `apps/klippy/clients/android`; see its README
for the available toolchain and tasks.

## Run Locally

The simplest complete deployment uses Docker Compose:

```bash
# Auth server on 8081 and clipboard server on 8080
docker compose -f apps/klippy/docker-compose.yml up --build

# Combined deployment (auth + clipboard routes in one JVM on 8080)
docker compose -f apps/combined-server/docker-compose.yml up --build
```

The separate deployment uses `http://localhost:8081` for auth and
`http://localhost:8080` for clipboard requests. The combined deployment uses
`http://localhost:8080/auth` and `http://localhost:8080/api` for auth and
clipboard respectively, and also launches the bucket proxy from the same
combined env file.

For a host-based Linux development stack, configure the repository-root `.env`
and start each service in its own terminal:

```bash
# Terminal 1: auth server
mvn -pl apps/auth/http-based/server spring-boot:run
# Terminal 2: clipboard server
mvn -pl apps/klippy/server spring-boot:run
# Terminal 3: file-locker
mvn -pl apps/klippy/clients/file-locker -am package && java -jar apps/klippy/clients/file-locker/target/klippy-file-locker-0.1.0-SNAPSHOT-exec.jar
# Terminal 4: linux client
mvn -pl apps/klippy/clients/linux -am package && java -jar apps/klippy/clients/linux/target/klippy-linux-client-0.1.0-SNAPSHOT.jar
```

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
- [Combined server](../combined-server/README.md)
- [Shared server bootstrap](../../packages/server-bootstrap/README.md)
- [Authentication modules](../auth/README.md)
- [HTTP auth server](../auth/http-based/server/README.md)
- [HTTP API contracts](../auth/http-based/api/README.md)
- [HTTP auth client](../auth/http-based/client/README.md)
- [macOS client](clients/mac/README.md)
- [Linux client](clients/linux/README.md)
- [Offline sync client](clients/offline-sync/README.md)
- [Offline file-locker](clients/file-locker/README.md)
- [Dummy client](clients/dummy/README.md)
- [Android client](clients/android/README.md)
- [Azure infrastructure](devops/README.md)

The server READMEs document the HTTP contracts, configuration, and deployment
details. Each client README covers only that client's setup and behavior.
