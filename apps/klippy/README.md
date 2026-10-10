# Klippy

Klippy records text clipboard changes from desktop and Android clients in a
Spring Boot server backed by PostgreSQL.

The Java code is a JDK 25 multi-module Maven project. The Android client is a
separate Kotlin/Gradle project under `apps/klippy/clients/android`.

Authentication is supplied by the sibling `apps/auth` directory in this repository.
See the root `README.md` for toolchain requirements and the repo-wide build and
test commands; the Android client is built with Gradle instead, as its README describes.

## Run Locally

The simplest way to run the servers is the whole-stack Docker Compose at the repository root. It
is two files — `docker-compose.storages.yml` holds the shared PostgreSQL and Redis,
`docker-compose.all-services.yml` the applications — and they declare one project, so passing both
brings up storage, the auth and clipboard servers, and an Nginx entrypoint together:

```bash
docker compose -f docker-compose.storages.yml -f docker-compose.all-services.yml up --build
```

Nginx is the single entrypoint (port 8080 by default, overridable with
`ORWELL_HTTP_PORT`): auth is served at `http://localhost:8080/auth` and clipboard
requests at `http://localhost:8080/clipboard`.

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

Create an identity once on the auth server. Identities live in a workspace, and creating one needs
a token from the server's `admin` workspace.

**The klippy desktop clients are pinned to `def`.** `ClientAuthSession` accepts a workspace, but no
klippy entry point reads one from the environment, so setting `AUTH_WORKSPACE` for a client does
nothing — create its identity in `def`. (The `AUTH_WORKSPACE` in `scripts/test-dummy-client.sh` is
that script's own shell variable, steering the `curl` URLs it builds, not something the client
reads.) `CODE_REFACTOR.md` tracks lifting this.

```bash
ADMIN_TOKEN=$(curl -s http://localhost:8080/auth/admin/login \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"ops-admin","secret":"change-me-please"}' | jq -r .token)

curl -i http://localhost:8080/auth/def/identities \
  -H 'Content-Type: application/json' \
  -H "X-Client-Id: ops-admin" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"clientId":"dummy","secret":"change-me-please"}'
```

The first admin comes from `AUTH_ADMIN_BOOTSTRAP_CLIENT_ID` / `AUTH_ADMIN_BOOTSTRAP_SECRET` on
the auth server — see
[its README](../auth/http-based/server/README.md).

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
