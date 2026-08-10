# gmail-general

Polls **many** Gmail mailboxes over **IMAP** on a fixed interval (a scheduled job, not a
persistent IDLE connection), stores each new Inbox message as a row in the `gmail` Postgres
database owned by the mailbox it came from, and POSTs each newly-stored message to the webhook
subscribers of that mailbox. Stored mail is readable back over HTTP via `GET /mails` and
`GET /mails/latest`; see [API](#api) below. There is no inbound HTTP trigger for ingestion.

Mailboxes are **rows, not configuration**. Each one is a `users` row holding the mailbox address
and the client id allowed to read it, with its IMAP app password in a one-to-one `secrets` row.
Register them with `POST /users` and `PUT /users/{id}/secret`; there is no `IMAP_USERNAME` any
more, and a service with no user rows polls nothing.

Reads are scoped to the caller: `GET /mails` resolves the mailbox from the authenticated bearer
token's client id, and there is deliberately no parameter that selects a user — one consumer
cannot reach another's mail even by guessing an id.

**Push is scoped the same way.** A consumer registers a URL with `POST /subscriptions`, and that
URL receives only the mail of the mailbox the calling client id owns. Subscriptions are rows in
`webhook_subscriptions`, not configuration, so adding or removing a receiver takes effect on the
next delivery without a restart. The one exception is the legacy `GMAIL_WEBHOOK_CLIENTS` list,
which broadcasts **every** mailbox's mail to every URL in it — see
[Webhook subscriptions](#webhook-subscriptions).

Before every client webhook delivery, gmail-general calls the auth server's `/login` endpoint with
`AUTH_CLIENT_ID` and `AUTH_CLIENT_SECRET`, then sends the message with the returned bearer token
and `X-Client-Id`. `AUTH_BASE_URL` — the auth server's base URL — is **required**; unset, the
service exits at startup with a validation error. The receiving app checks those headers with the
auth server. Webhook forwarding is otherwise optional: with no subscriptions and no
`GMAIL_WEBHOOK_CLIENTS`, the service only stores mail.

## Configuration

| Environment variable | Example | Purpose |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address for the health/mail endpoints. |
| `SERVER_PORT` | `9100` | HTTP port for the health/mail endpoints. |
| `IMAP_HOST` | `imap.gmail.com` | IMAP server host. Optional, defaults to `imap.gmail.com`. |
| `IMAP_PORT` | `993` | IMAP port. Optional, defaults to `993`. |
| `IMAP_SSL` | `true` | Connect over TLS (`imaps`) with certificate identity checking. Optional, defaults to `true`; set `false` only for a plaintext local test server. |
| `IMAP_FOLDER` | `INBOX` | Folder to poll, for every mailbox. Optional, defaults to `INBOX`. |
| `GMAIL_POLL_INTERVAL_SECONDS` | `60` | How often to poll each mailbox. Optional, defaults to `60`. |
| `GMAIL_POLL_CONCURRENCY` | `4` | How many mailboxes may be polled at once. Optional, defaults to `4`. Mailboxes are polled on a bounded pool so one slow or hanging mailbox does not delay the rest; raise it if you have many mailboxes and polls overlap. |
| `GMAIL_DELIVERY_INTERVAL_SECONDS` | `5` | How often the webhook delivery job walks each subscription's cursor forward. Optional, defaults to `5`. This is the upper bound on webhook latency. |
| `GMAIL_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/gmail` | PostgreSQL JDBC URL. **Required.** |
| `GMAIL_DATASOURCE_USERNAME` | `gmail` | Database username. **Required.** |
| `GMAIL_DATASOURCE_PASSWORD` | `gmail` | Database password. **Required.** |
| `GMAIL_JPA_HIBERNATE_DDL_AUTO` | `update` | Hibernate schema-management mode. **Required.** |
| `GMAIL_JPA_JDBC_TIME_ZONE` | `UTC` | Hibernate JDBC timezone. **Required.** |
| `GMAIL_ROUTE_PREFIX` | `/gmail` | Optional. Path prefix the `/mails` routes are served under; defaults to empty. `/health` is never prefixed. |
| `GMAIL_WEBHOOK_CLIENTS` | `http://127.0.0.1:9200/analyzer/email` | **Legacy broadcast.** Comma-separated webhook URLs that receive *every* mailbox's mail, ignoring per-mailbox scoping. Optional and logged as a warning at startup when set; prefer `POST /subscriptions`. |
| `AUTH_BASE_URL` | `http://127.0.0.1:8081` | Auth server base URL. **Required.** |
| `AUTH_CLIENT_ID` | `gmail-general` | Client id used for webhook-delivery login. |
| `AUTH_CLIENT_SECRET` | | Client secret used for webhook-delivery login. |

## Gmail app password

IMAP with a password requires an **app password**, not the account login password:

1. Enable 2-Step Verification on the Google account.
2. Create an app password (Google Account → Security → App passwords) and send the 16-character
   value to `PUT /users/{id}/secret` for that mailbox.
3. IMAP is enabled by default on Gmail; if it was turned off, re-enable it in Gmail settings under
   *Forwarding and POP/IMAP*.

No Google Cloud project, OAuth client, or Pub/Sub topic is required.

## Checking credentials without starting the service

`scripts/check-imap-creds.sh` connects over IMAP, logs in, and fetches the headers of the most
recent message in the configured folder — a quick way to confirm a mailbox's credentials before
registering it. It takes `IMAP_USERNAME`/`IMAP_PASSWORD` from the environment for its own use
only; the service itself no longer reads either (the other `IMAP_*` defaults still match
`GmailEnvs`). If `IMAP_PASSWORD` isn't set it prompts for it with hidden input.

```bash
set -a; source .env; set +a
./scripts/check-imap-creds.sh
```

## Database

Uses the shared Postgres instance defined in `docker-compose.all-services.yml`
(`db-init/all-services.sql` creates the `gmail` role and database). Hibernate manages the schema
(`GMAIL_JPA_HIBERNATE_DDL_AUTO=update`); the persistent tables are:

- `users` — one row per polled mailbox: `email` is the IMAP login, `client_id` is the auth-server
  client id allowed to read that mailbox's mail. Both are unique.
- `secrets` — the IMAP app password for a user, one row per user (`user_id` is unique). **The
  password is stored in plaintext**, so anyone with read access to this database, a backup, or a
  replica holds every registered mailbox's live credentials. Encrypting the column, or holding
  only a reference into `secrets-manager`, is the upgrade path if that is not acceptable.
- `email_messages` — one row per stored mail, owned by a `user_id`. `id` is an auto-increment
  surrogate key assigned in insertion order, which doubles as the consumption cursor accepted by
  `?checkpoint=` (see [API](#api)); ids come from one sequence shared by all users, so a single
  user's ids increase but are not contiguous. `message_id` (the `Message-ID` header, or a
  `uid-<uid>` fallback for messages that lack one) is unique **per user** and is the dedup key —
  one mail addressed to two registered mailboxes is stored once for each, which a global
  constraint would have silently prevented.
- `imap_checkpoints` — one row per `(user, folder)`, tracking `uid_validity` and `last_uid` so a
  restart resumes from where the poller left off instead of re-delivering the whole mailbox.
  Keyed per user because UIDs are only meaningful within one account.
- `webhook_subscriptions` — one row per `(user, url)`: which endpoints receive a given mailbox's
  mail. Fan-out reads this table on each delivery, so adding or removing a subscriber needs no
  restart. `url` is unique per user rather than globally, because two users may legitimately point
  at the same receiver. `last_delivered_id` is the delivery cursor — the highest `email_messages.id`
  that receiver has acknowledged — so a subscriber that was down catches up instead of losing mail.
  `active` allows pausing a subscription without losing the row; nothing sets
  it to `false` today.

### Upgrading an existing database

`spring.jpa.hibernate.ddl-auto=update` adds tables, columns and indexes but never drops a primary
key or a unique constraint, so it cannot perform this change on its own. Run
[`migrations/001-multi-user.sql`](migrations/001-multi-user.sql) against the `gmail` database
before starting the new jar; it explains the choice it needs from you about existing rows. Skipping
it leaves the old global `message_id` unique index and the old `folder` primary key in place, both
of which break multi-mailbox operation *silently* — mail simply goes missing.

### Dropping `thread_id` on an existing database

`thread_id` was a Gmail API field that IMAP has no equivalent for; it was always stored empty and
has been removed. `ddl-auto=update` only adds columns, never drops them, so a database created
before this change still has a `thread_id NOT NULL` column that nothing populates. **Run this
before deploying:**

```sql
ALTER TABLE email_messages DROP COLUMN thread_id;
```

A database created fresh after this change never has the column and needs nothing.

Deploying without dropping the column first *loses mail*, quietly. Every insert fails the
not-null constraint, and the poller deliberately advances its UID cursor past a message it could
not process (so one bad message can't wedge the mailbox) — so each failed message is skipped
permanently, recorded only as a `Failed to process IMAP message.` error log. Restarting does not
re-fetch it, because the checkpoint has already moved past it. To recover, drop the column, then
rewind the checkpoint to the last UID that stored successfully:

```sql
UPDATE imap_checkpoints SET last_uid = <last good uid> WHERE folder = 'INBOX';
```

Re-fetched messages are deduped on `message_id`, so rewinding too far is safe — already-stored
mail is skipped rather than duplicated.

## API

All endpoints return JSON and are served under `GMAIL_ROUTE_PREFIX` (empty by default). Mail
content is sensitive, so every route requires the same `X-Client-Id` +
`Authorization: Bearer <token>` headers as any other `@RequireAuthentication`-guarded endpoint in
this repo; the token is checked against the auth server at `AUTH_BASE_URL`.

**`/mails` routes are scoped to the caller.** The mailbox served is the one whose `users.client_id`
matches the authenticated client id — there is no parameter that selects a user. A valid token
whose client id owns no mailbox gets `403`.

**`/subscriptions` routes are scoped to the caller** in exactly the same way: a consumer can only
subscribe, list, and delete receivers for its own mailbox.

**`/users` routes are not scoped.** Any authenticated caller can register a mailbox and set any
mailbox's password, so the credential used to reach them should not be given to ordinary mail
consumers.

### Register a mailbox

```http
POST /users
{"email": "you@gmail.com", "clientId": "your-consumer-client-id"}
```

`201` with the created user. `email` and `clientId` are both unique; a duplicate is `409`. The
mailbox is not polled until its secret is set — until then the poller skips it with a warning.

```http
PUT /users/{id}/secret
{"imapPassword": "<16-char app password>"}
```

`204`, and `404` if no such user. Idempotent — calling it again replaces the password rather than
adding a second row. The value is write-only: no endpoint ever returns it.

```http
GET /users
```

Every registered mailbox, without secrets.

> A newly registered mailbox starts from its **current head**: mail already sitting in it is not
> replayed, only what arrives after the first poll. Register the mailbox before you expect to
> receive anything through it.

### Webhook subscriptions

A subscription is a URL that receives the caller's mail shortly after it is stored. Which mailbox it
drains is taken from the authenticated client id, never from the request — there is no way to
subscribe to someone else's mail.

```http
POST /subscriptions
{"url": "https://receiver.example.com/mail"}
```

`201` with the created subscription. The URL must be absolute `http`/`https` with a host, otherwise
`400`. Re-posting a URL already subscribed to your mailbox is `409` rather than a second row, so a
retried registration cannot double every delivery.

```http
GET /subscriptions
DELETE /subscriptions/{id}
```

`GET` lists the caller's own subscriptions, oldest first. `DELETE` returns `204`, and `404` both
for an id that does not exist and for one belonging to another user — the route cannot be used to
discover which ids are taken.

Each delivery is a POST of one message, carrying `X-Client-Id` and `Authorization: Bearer <token>`
obtained from the auth server:

```json
{
  "id": "<abc@example.com>",
  "account": "you@gmail.com",
  "subject": "Hello there",
  "from": "Alice <alice@example.com>",
  "to": "you@gmail.com",
  "receivedAt": 1753178400000,
  "body": "Body text here."
}
```

`account` is the mailbox the message was polled from — taken from the `users` row, not from a
header, since mail can arrive by Bcc, alias, or forwarding and `to` is then not the owner. A
per-mailbox subscriber always sees the same value; a `GMAIL_WEBHOOK_CLIENTS` broadcast receiver
uses it to tell mailboxes apart.

**Delivery is cursor-tracked, and catches up.** Each subscription carries a `lastDeliveredId` — the
highest mail `id` that receiver has acknowledged with a 2xx. Every `GMAIL_DELIVERY_INTERVAL_SECONDS`
a job walks each subscription forward from its cursor, oldest first, and advances it only on
success. A receiver that was down, slow, or returning errors is therefore re-sent what it missed on
a later round instead of losing it.

Two consequences worth designing for:

- **At least once, not exactly once.** A receiver that processes a message and then fails to return
  2xx — including a connection dropped after it replied — will be sent that message again. Key on
  `id`, which is stable per message and per mailbox.
- **Order is preserved per subscription.** Delivery stops at the first failure for that
  subscription, so a receiver never sees later mail before the message it rejected. Other
  subscriptions are unaffected and continue in the same round.

A new subscription's cursor starts at the mailbox's **current head**, so subscribing does not replay
everything already stored. Subscribe before you expect to receive anything.

Migrating off `GMAIL_WEBHOOK_CLIENTS` is safe to do gradually: a URL the user has also subscribed is
skipped by the broadcast, so it is delivered once — through the cursor-tracked path — rather than
twice. Subscribe first, then unset the variable. Note the broadcast itself is **not** cursor-tracked
and stays best-effort: one attempt, failures logged and dropped. That is another reason to migrate.

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
