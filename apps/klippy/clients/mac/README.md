# Clippy macOS Client

Foreground Java client that posts changed macOS text clipboard content to the
Clippy server.

## Requirements

- JDK 25+ and Maven 3.9+
- Running Clippy auth, clipboard, and file-locker services
- A logged-in graphical session so Java can access the system clipboard

## Configure

Create or update `.env` in the repository root:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=my-mac
CLIENT_SECRET=change-me-please
CLIPBOARD_POLL_INTERVAL_MS=1000
```

`REMOTE_SERVER_URL` is required and may be the server base URL or the full
`/clipboard` endpoint. `CLIENT_ID` defaults to the machine hostname and
`CLIPBOARD_POLL_INTERVAL_MS` defaults to `1`.

With `CLIENT_SECRET`, the client logs in at startup and refreshes its token after
a `401`; `AUTH_SERVER_URL` is then required. For static authentication, omit
`CLIENT_SECRET` and set `CLIENT_TOKEN` to a token returned by the auth server's
`/login` endpoint. See the [auth server documentation](../../../auth/server/README.md)
for identity creation and login requests.

Shell environment variables override `.env` values.

## Run

Start the file-locker from the repository root:

```bash
./scripts/start-file-locker.sh
```

Then build and run the client in another terminal:

```bash
mvn -pl clients/mac -am package
java -jar clients/mac/target/clippy-client-0.1.0-SNAPSHOT.jar
```

If a remote write fails, the client asks the file-locker to append the payload
to `clippy-offline-clipboard.json`. The client exits at startup when the
file-locker is unavailable, preventing uncoordinated file writes.

## Test

```bash
mvn -pl clients/mac -am test
```
