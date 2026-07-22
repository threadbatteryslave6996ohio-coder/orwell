# Klippy Linux Client

Java clipboard client for posting Linux text clipboard changes to the Klippy server.

The client is an explicit foreground process. It reads the local text clipboard,
checks for a change from the last successfully sent value, and posts changed
content to the server.

## Requirements

- A graphical Linux desktop session
- JDK 25+ with `javac` available on `PATH`
- Maven 3.9+
- A running Klippy auth server and app server
- `wl-clipboard` for GNOME Wayland, or `xclip`/`xsel` for X11

Install the recommended Ubuntu packages:

```bash
sudo apt install openjdk-25-jdk maven wl-clipboard xclip
```

## Run the Client

Start the shared database, then run the auth server, app server, and client. The
repository has exactly one PostgreSQL; it serves the `klippy`, `auth`, and
`secrets` databases on port `5432` and is provisioned by `db-init/all-services.sql`:

```bash
docker compose -f docker-compose.all-services.yml up -d db
```

The client reads configuration from `.env` in the repository root:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=ubuntu-gnome
CLIENT_SECRET=change-me-please
CLIPBOARD_POLL_INTERVAL_MS=1000
```

`REMOTE_SERVER_URL` is required and may be either the server base URL or the
full `/clipboard` endpoint. `CLIENT_ID` is optional; if omitted, the client
uses the machine hostname with a random fallback. `CLIPBOARD_POLL_INTERVAL_MS`
defaults to `1000` and must be at least `100`. A lower value is fatal: the
client reports it on stderr and in `logs/klippy-client.txt`, then exits with
status `1`. `CLIENT_SECRET` enables startup login
and token refresh; omit it and set `CLIENT_TOKEN` if you want to use a static
auth token instead.

Shell environment variables override values from `.env` when both are set.

Start the file-locker from the repository root:

```bash
./apps/klippy/scripts/start-file-locker.sh
```

Keep the file-locker running, then start the client in another terminal:

```bash
./apps/klippy/scripts/start-linux-client.sh
```

The launcher changes to `apps/klippy` before starting Java. The client still
finds the repository-root `.env` because `EnvFiles.load()` walks up the parent
directories from the working directory.

Offline clipboard entries are appended through the file-locker service over a
Unix-domain socket. The client exits at startup if it cannot connect, preventing
uncoordinated direct writes while a sync is reading the file.

## Clipboard Backend

Backend selection is automatic:

- Java AWT is preferred because it keeps one persistent clipboard connection.
- GNOME Wayland falls back to `wl-paste` from `wl-clipboard`.
- X11 falls back to `xclip` when installed, then `xsel`.

The command backends run a short-lived helper on each poll. They are fallbacks
because that process churn can produce transient application entries on some
desktops. Klippy also removes desktop startup and activation metadata from
those helpers.

Override automatic selection with `CLIPBOARD_BACKEND`:

```bash
export CLIPBOARD_BACKEND=awt
```

Supported values are `wl-paste`, `wayland`, `xclip`, `xsel`, `awt`, and `java`.

## Server Contract

The client sends:

```http
POST /clipboard
Authorization: Bearer <client-token>
Content-Type: application/json
```

```json
{
  "clientId": "ubuntu-gnome",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```

## Test

```bash
mvn -pl apps/klippy/clients/linux -am test
```
