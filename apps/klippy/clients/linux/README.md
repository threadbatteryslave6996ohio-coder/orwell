# Clippy Linux Client

Java clipboard client for posting Ubuntu GNOME text clipboard changes to the Clippy server.

The client is an explicit foreground process. It reads the local text clipboard, checks for a change from the last successfully sent value, and posts changed content to the server.

## Requirements

- Ubuntu GNOME on ARM or x86_64
- JDK 25+ with `javac` available on `PATH`
- Maven 3.9+
- A running Clippy auth server and app server
- `wl-clipboard` for GNOME Wayland, or `xclip`/`xsel` for X11

Install the recommended Ubuntu packages:

```bash
sudo apt install openjdk-25-jdk maven wl-clipboard xclip
```

## Run the Client

Start the auth database on port `5433` and the app database on port `5432` using your preferred local PostgreSQL setup, then run the auth server, app server, and client.

The client also reads configuration from `.env` in the repository root:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=ubuntu-gnome
CLIENT_SECRET=change-me-please
CLIPBOARD_POLL_INTERVAL_MS=1000
CLIPBOARD_BACKEND=wl-paste
```

Shell environment variables override values from `.env` when both are set.

Build and start the client with the repository launcher:

```bash
./scripts/start-file-locker.sh
```

Keep the file-locker running, then start the client in another terminal:

```bash
./scripts/start-linux-client.sh
```

The launcher changes to the repository root before starting Java, so the client
consistently finds the repository root `.env` file.

Offline clipboard entries are appended through the file-locker service over a
Unix-domain socket. The client exits at startup if it cannot connect, preventing
uncoordinated direct writes while a sync is reading the file.

`REMOTE_SERVER_URL` is required. It may be either the server base URL, such as `http://localhost:8080`, or the full endpoint, such as `http://localhost:8080/clipboard`.

`CLIENT_ID` is optional and defaults to the machine hostname, with a random fallback if hostname lookup fails. `CLIPBOARD_POLL_INTERVAL_MS` is optional and defaults to `1000`.

`CLIENT_SECRET` lets the client log in to the auth server and refresh tokens when the server returns `401`. `AUTH_SERVER_URL` is required when `CLIENT_SECRET` is set. If you prefer a static token, keep `CLIENT_SECRET` unset and provide `CLIENT_TOKEN` instead.

## Clipboard Backend

Backend selection is automatic:

- Java AWT is preferred because it keeps one persistent clipboard connection.
- GNOME Wayland falls back to `wl-paste` from `wl-clipboard`.
- X11 falls back to `xclip` when installed, then `xsel`.

The command backends run a short-lived helper on each poll. They are fallbacks
because that process churn can produce transient application entries on some
desktops. Clippy also removes desktop startup and activation metadata from
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
