# Klippy macOS Client

Foreground Java client that posts changed macOS text clipboard content to the
Klippy server. It holds the terminal it is started from; it does not daemonize.

Reading the clipboard requires AWT, which by default would register the JVM as a
regular macOS app — giving it a Dock icon and letting it steal keyboard focus.
The client sets `apple.awt.UIElement=true` in `main`, before the AWT toolkit
initializes, so it runs as an accessory app instead: no Dock icon, no focus
stealing, clipboard still readable. This applies however you launch it — no
command-line flag is needed. To debug with a Dock icon, launch with an explicit
`-Dapple.awt.UIElement=false`, which the client leaves alone.

## Requirements

- JDK 25+ and Maven 3.9+
- Running Klippy auth, clipboard, and file-locker services
- A logged-in graphical session so Java can access the system clipboard

## Configure

Create or update `.env` in the repository root:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=my-mac
CLIENT_SECRET=change-me-please
CLIPBOARD_POLL_INTERVAL_MS=1000
CLIENT_HEARTBEAT_INTERVAL_MS=5000
```

`REMOTE_SERVER_URL` is required and may be the server base URL or the full
`/clipboard` endpoint. `CLIENT_ID` is optional; if omitted, the client uses the
machine hostname with a random fallback. `CLIPBOARD_POLL_INTERVAL_MS`
defaults to `1000` and must be at least `100`. A lower value is fatal: the
client reports it on stderr and in `logs/klippy-client.txt`, then exits with
status `1`.

Alongside clipboard polling, the client posts a liveness heartbeat to the
server's `POST /heartbeat` every `CLIENT_HEARTBEAT_INTERVAL_MS` (default `5000`),
on its own schedule independent of the clipboard poll. The server logs each beat
to Loki, where `apps/liveness-analyzer` watches for the absence of beats to detect
that the client has stopped running. The client stays quiet on success and only
logs when a beat cannot be delivered.

With `CLIENT_SECRET`, the client logs in at startup and refreshes its token
after a `401`; `AUTH_SERVER_URL` is then required. For static authentication,
omit `CLIENT_SECRET` and set `CLIENT_TOKEN` to a token returned by the auth
server's `/login` endpoint. See the
[auth server documentation](../../../auth/http-based/server/README.md) for
identity creation and login requests.

Shell environment variables override `.env` values.

## Run

Start the file-locker from the repository root:

```bash
./apps/klippy/scripts/start-file-locker.sh
```

Then build and run the client in another terminal:

```bash
mvn -pl apps/klippy/clients/mac -am package
java -jar apps/klippy/clients/mac/target/klippy-mac-client-0.1.0-SNAPSHOT.jar
```

If a remote write fails, the client asks the file-locker to append the payload
to `klippy-offline-clipboard.json`. The client exits at startup when the
file-locker is unavailable, preventing uncoordinated file writes.

## Test

```bash
mvn -pl apps/klippy/clients/mac -am test
```
