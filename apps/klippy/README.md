# Klippy

Klippy records text clipboard changes from desktop and Android clients in a
Spring Boot server backed by PostgreSQL.

The Java code is a JDK 25 multi-module Maven project. The Android client is a
separate Kotlin/Gradle project under `apps/klippy/clients/android`.

Authentication is supplied by the sibling `apps/auth` directory in this repository.
See the root `README.md` for toolchain requirements and the repo-wide build and
test commands; the Android client is built with Gradle instead, as its README describes.

## Run Locally

The simplest way to run the servers is Docker Compose. This stack contains no
database of its own — both servers connect out to `host.docker.internal:5432` —
so the shared PostgreSQL must already be running:

```bash
# The one shared Postgres, serving the klippy and auth databases
docker compose -f docker-compose.all-services.yml up -d db

# Nginx proxy on 8080; auth and clipboard stay on the Docker network
docker compose -f apps/klippy/docker-compose.yml up --build
```

Nginx is the single entrypoint: auth is served at `http://localhost:8080/auth` and
clipboard requests at `http://localhost:8080/clipboard`. Set `KLIPPY_HTTP_PORT` to
publish the proxy on a port other than 8080.

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
curl -i http://localhost:8080/auth/identities \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

Java clients can log in and refresh tokens automatically with this
repository-root `.env` configuration:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8080/auth
CLIENT_ID=dummy
CLIENT_SECRET=change-me-please
```

Alternatively, call `/login` and provide the returned value as `CLIENT_TOKEN`.
Shell environment variables override `.env` values.

## Documentation

- [Clipboard server](server/README.md)
- [macOS client](clients/mac/README.md)
- [Linux client](clients/linux/README.md)
- [Offline sync client](clients/offline-sync/README.md)
- [Offline file-locker](clients/file-locker/README.md)
- [Dummy client](clients/dummy/README.md)
- [Android client](clients/android/README.md)

The server README documents the HTTP contract, configuration, and deployment
details. Each client README covers only that client's setup and behavior. The
auth and shared-package READMEs are linked from the root `README.md`.
