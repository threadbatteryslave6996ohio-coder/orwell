# gmail-general

Polls a Gmail mailbox over **IMAP** on a fixed interval (a scheduled job, not a persistent IDLE
connection), stores each new Inbox message as a row in the `gmail` Postgres database, and — if any
`GMAIL_WEBHOOK_CLIENTS` are configured — POSTs each newly-stored message to that comma-separated
list. Stored mail is readable back over HTTP via `GET /mails` and `GET /mails/latest`; see
[API](#api) below. There is no inbound HTTP trigger for ingestion.

Before every client webhook delivery, gmail-general calls the auth server's `/login` endpoint with
`AUTH_CLIENT_ID` and `AUTH_CLIENT_SECRET`, then sends the message with the returned bearer token
and `X-Client-Id`. `AUTH_BASE_URL` — the auth server's base URL — is **required**; unset, the
service exits at startup with a validation error. The receiving app checks those headers with the
auth server. Webhook forwarding is otherwise optional: leave `GMAIL_WEBHOOK_CLIENTS` unset and the
service only stores mail.

## Configuration

| Environment variable | Example | Purpose |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address for the health/mail endpoints. |
| `SERVER_PORT` | `9100` | HTTP port for the health/mail endpoints. |
| `IMAP_HOST` | `imap.gmail.com` | IMAP server host. Optional, defaults to `imap.gmail.com`. |
| `IMAP_PORT` | `993` | IMAP port. Optional, defaults to `993`. |
| `IMAP_SSL` | `true` | Connect over TLS (`imaps`) with certificate identity checking. Optional, defaults to `true`; set `false` only for a plaintext local test server. |
| `IMAP_USERNAME` | `you@gmail.com` | Mailbox address. **Required.** |
| `IMAP_PASSWORD` | `<app password>` | Mailbox app password (see below). **Required.** |
| `IMAP_FOLDER` | `INBOX` | Folder to poll. Optional, defaults to `INBOX`. |
| `GMAIL_POLL_INTERVAL_SECONDS` | `60` | How often to poll the mailbox. Optional, defaults to `60`. |
| `GMAIL_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/gmail` | PostgreSQL JDBC URL. **Required.** |
| `GMAIL_DATASOURCE_USERNAME` | `gmail` | Database username. **Required.** |
| `GMAIL_DATASOURCE_PASSWORD` | `gmail` | Database password. **Required.** |
| `GMAIL_JPA_HIBERNATE_DDL_AUTO` | `update` | Hibernate schema-management mode. **Required.** |
| `GMAIL_JPA_JDBC_TIME_ZONE` | `UTC` | Hibernate JDBC timezone. **Required.** |
| `GMAIL_ROUTE_PREFIX` | `/gmail` | Optional. Path prefix the `/mails` routes are served under; defaults to empty. `/health` is never prefixed. |
| `GMAIL_WEBHOOK_CLIENTS` | `http://127.0.0.1:9200/analyzer/email` | Comma-separated webhook URLs. Optional; leave unset to only store mail. |
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

## Database

Uses the shared Postgres instance defined in `docker-compose.all-services.yml`
(`db-init/all-services.sql` creates the `gmail` role and database). Hibernate manages the schema
(`GMAIL_JPA_HIBERNATE_DDL_AUTO=update`); the persistent tables are:

- `email_messages` — one row per stored mail. `id` is an auto-increment surrogate key assigned in
  insertion order, which doubles as the consumption cursor accepted by `?checkpoint=` (see
  [API](#api)). `message_id` (the `Message-ID` header, or a `uid-<uid>` fallback for messages that
  lack one) is unique and is the dedup key checked before each insert.
- `imap_checkpoints` — one row per polled folder, tracking `uid_validity` and `last_uid` so a
  restart resumes from where the poller left off instead of re-delivering the whole mailbox. This
  replaces the old `.imap-uid` checkpoint file.

## API

All endpoints return JSON and are served under `GMAIL_ROUTE_PREFIX` (empty by default).

### List recent mail

```http
GET /mails?limit=50
```

Returns up to `limit` (default 50, max 500) messages, most recently received first.

### Latest mail / incremental consumption

```http
GET /mails/latest
```

Returns the single most recently stored message, or `204 No Content` if the mailbox has nothing
stored yet.

```http
GET /mails/latest?checkpoint=<last-id-you-have-consumed>&limit=50
```

Returns a JSON array of every message with `id` greater than `checkpoint`, oldest first, bounded by
`limit` (default 50, max 500). A consumer's poll loop is: call `GET /mails/latest` once to get
started, remember its `id`, then repeatedly call `GET /mails/latest?checkpoint=<that id>`, each time
advancing its remembered checkpoint to the highest `id` it received.

Each message object:

```json
{
  "id": 42,
  "messageId": "<abc@example.com>",
  "threadId": "",
  "subject": "Hello there",
  "from": "Alice <alice@example.com>",
  "to": "bob@example.com",
  "receivedAt": "2026-07-22T10:00:00Z",
  "body": "Body text here."
}
```

## Build and run

```bash
docker compose -f docker-compose.all-services.yml up -d db
mvn -pl apps/google/gmail-general -am package
java -jar apps/google/gmail-general/target/gmail-general-0.1.0-SNAPSHOT-exec.jar
```

On start the poller connects, and on its first run for a given `IMAP_FOLDER` records the current
mailbox head in the `imap_checkpoints` table so only mail that arrives afterward is stored — the
existing mailbox history is not backfilled. Every `GMAIL_POLL_INTERVAL_SECONDS` it reconnects,
fetches anything newer than its checkpoint, stores it, and advances the checkpoint. Messages that
land while the service is down are picked up on the next poll (progress is tracked by IMAP UID,
keyed on `UIDVALIDITY`).
