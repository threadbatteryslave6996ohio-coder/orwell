# Klippy Dummy Client

Command-line Java client that sends text as clipboard content without reading a
system clipboard.

## Configure

Start the auth and clipboard servers, create an identity as described in the
[auth server documentation](../../../auth/http-based/server/README.md), then
configure the repository-root `.env`:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=dummy
CLIENT_SECRET=change-me-please
```

`REMOTE_SERVER_URL` is required and may be the server base URL or the full
`/clipboard` endpoint. With `CLIENT_SECRET`, the client logs in and refreshes
its token automatically; `AUTH_SERVER_URL` is then required. For static
authentication, omit `CLIENT_SECRET` and provide `CLIENT_TOKEN` instead.

`CLIENT_ID` defaults to `dummy-` plus the machine hostname. Shell environment
variables override `.env` values.

At startup the client prints a redacted snapshot of the loaded client
environment values to the console. `CLIENT_SECRET` and similar credential-like
values are masked.

## Run

Build and send one command from the repository root:

```bash
mvn -pl apps/klippy/clients/dummy -am package
java -jar apps/klippy/clients/dummy/target/klippy-dummy-client-0.1.0-SNAPSHOT.jar "ping"
```

You can also pipe commands on standard input. Each non-empty line is sent as a
separate request:

```bash
printf 'ping\nstatus\n' | java -jar apps/klippy/clients/dummy/target/klippy-dummy-client-0.1.0-SNAPSHOT.jar
```

One-shot commands exit with status `1` when the server is unreachable or
rejects the request. The client logs the endpoint and HTTP status without
printing credentials.

## Test

```bash
mvn -pl apps/klippy/clients/dummy -am test
```
