# gmail-general

Watches a Gmail mailbox over **IMAP** (using IMAP IDLE for near real-time delivery), saves each
new Inbox message as `GMAIL_STORE_DIR/<message-id>.json`, and POSTs each saved message to the
comma-separated `GMAIL_WEBHOOK_CLIENTS` list. There is no inbound HTTP trigger — a background
listener holds the IMAP connection open and reconnects on failure; the server itself exposes only
the shared `/health` endpoint.

Before every client webhook delivery, gmail-general calls the auth server's `/login` endpoint with
`AUTH_CLIENT_ID` and `AUTH_CLIENT_SECRET`, then sends the message with the returned bearer token
and `X-Client-Id`. `AUTH_BASE_URL` — the auth server's base URL — is **required**; unset, the
service exits at startup with a validation error. The receiving app checks those headers with the
auth server.

## Configuration

| Environment variable | Example | Purpose |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address for the health endpoint. |
| `SERVER_PORT` | `9100` | HTTP port for the health endpoint. |
| `IMAP_HOST` | `imap.gmail.com` | IMAP server host. Optional, defaults to `imap.gmail.com`. |
| `IMAP_PORT` | `993` | IMAP port. Optional, defaults to `993`. |
| `IMAP_SSL` | `true` | Connect over TLS (`imaps`) with certificate identity checking. Optional, defaults to `true`; set `false` only for a plaintext local test server. |
| `IMAP_USERNAME` | `you@gmail.com` | Mailbox address. **Required.** |
| `IMAP_PASSWORD` | `<app password>` | Mailbox app password (see below). **Required.** |
| `IMAP_FOLDER` | `INBOX` | Folder to watch. Optional, defaults to `INBOX`. |
| `GMAIL_STORE_DIR` | `./data/gmail` | Directory where messages and the `.imap-uid` checkpoint are written. |
| `GMAIL_WEBHOOK_CLIENTS` | `http://127.0.0.1:9200/analyzer/email` | Comma-separated webhook URLs. |
| `AUTH_BASE_URL` | `http://127.0.0.1:8081` | Auth server base URL. **Required.** |
| `AUTH_CLIENT_ID` | `gmail-general` | Client id used for webhook-delivery login. |
| `AUTH_CLIENT_SECRET` | | Client secret used for webhook-delivery login. |

## Gmail app password

IMAP with a password requires an **app password**, not the account login password:

1. Enable 2-Step Verification on the Google account.
2. Create an app password (Google Account → Security → App passwords) and use the 16-character
   value as `IMAP_PASSWORD`.
3. IMAP is enabled by default on Gmail; if it was turned off, re-enable it in Gmail settings under
   *Forwarding and POP/IMAP*.

No Google Cloud project, OAuth client, or Pub/Sub topic is required.

## Checking credentials without starting the service

`scripts/check-imap-creds.sh` connects over IMAP, logs in, and fetches the headers of the most
recent message in the configured folder — a quick way to confirm `IMAP_USERNAME`/`IMAP_PASSWORD`
are correct before running the full service. It reads the same environment variables as the app
(defaults match `GmailEnvs`); if `IMAP_PASSWORD` isn't set it prompts for it with hidden input.

```bash
set -a; source .env; set +a
./scripts/check-imap-creds.sh
```

## Build and run

```bash
mvn -pl apps/google/gmail-general -am package
java -jar apps/google/gmail-general/target/gmail-general-0.1.0-SNAPSHOT-exec.jar
```

On start the listener connects, records the current mailbox head in `GMAIL_STORE_DIR/.imap-uid`,
and delivers only mail that arrives afterward. Messages that land while the service is down are
picked up on the next connect (progress is tracked by IMAP UID, keyed on `UIDVALIDITY`).
