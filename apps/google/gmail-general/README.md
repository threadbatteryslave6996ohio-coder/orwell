# gmail-general

Polls **many** Gmail mailboxes over **IMAP** on a fixed interval (a scheduled job, not a
persistent IDLE connection), stores each new Inbox message as a row in the `gmail` Postgres
database owned by the mailbox it came from, and POSTs each newly-stored message to the webhook
subscribers of that mailbox. Stored mail is readable back over HTTP via `GET /mails` and
`GET /mails/latest`; see [API](#api) below. There is no inbound HTTP trigger for ingestion.

**Messages are stored whole.** Both body renderings (`text/plain` and `text/html`), every header
including the repeated ones, an index of every attachment and inline image, and the complete RFC 822
source the message arrived as. The parsed columns are an index *over* that source rather than a
replacement for it, so a part this service's MIME parser mishandles is still recoverable, and
attachment bytes are served by reading them back out of it — stored once, not twice. The one
exception is size: a message above `GMAIL_MAX_MESSAGE_BYTES` (25 MB by default) is stored with its
headers, text bodies and attachment index but **without** its source, and is flagged `truncated`.
See [What is and is not stored](#what-is-and-is-not-stored).

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
| `GMAIL_MAX_MESSAGE_BYTES` | `26214400` | Largest message to archive in full. Optional, defaults to `26214400` (25 MiB, Gmail's own attachment ceiling). Above it a message is still stored — headers, both text bodies, attachment index — but its raw source is not, so its attachment bytes cannot be downloaded and it is flagged `truncated`. Raise it to archive bigger mail, lower it to cap what one message can cost the shared database. Never causes a message to be skipped. |
| `GMAIL_PUBLIC_BASE_URL` | `https://gmail.internal.example.com` | Base URL this service is reachable at from outside, used to make attachment URLs absolute in webhook payloads and API responses. Optional; when unset, those URLs are emitted as paths (`/mails/42/attachments/0`) and the receiver resolves them itself. Set it if your webhook receivers should be able to follow the URL as given. |
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

## What is and is not stored

Stored, per message:

- Both body renderings — the `text/plain` and `text/html` parts, as `body` and `bodyHtml`. A
  message with only one of them has the other as an empty string.
- **Every header**, in the order the message carried them, repeats included. `Received` lines stay
  in order because they are a delivery path read bottom-up; a map-shaped store would have kept only
  the last hop.
- **Every attachment and inline image**, indexed with filename, MIME type, decoded size, `Content-ID`
  and whether it is inline. Bytes are downloadable — see [Attachments](#attachments).
- **The complete RFC 822 source**, byte for byte. This is what makes the above an index rather than
  a lossy summary.

Not stored, and not obtainable over IMAP with a password:

- **Gmail labels, stars, and read/unread state.** IMAP exposes labels as folders and this service
  polls one folder; the rest are Gmail API concepts.
- **Threading.** `thread_id` was a Gmail API field with no IMAP equivalent and was removed.
- **Anything outside `IMAP_FOLDER`** (default `INBOX`) — no Sent, Drafts, Spam or Archive.
- **Mail that arrived before the mailbox was registered.** The first poll starts from the current
  head; history is not backfilled.

Also worth knowing:

- The mailbox is opened **read-only**. This service never writes to your Gmail — it does not mark
  mail read, move it, or delete it.
- Nothing propagates back. Deleting or re-labelling a message in Gmail does not change the stored
  copy; divergence after ingestion is expected, not a bug.
- Attachment bytes for a `truncated` message are gone: the source was never downloaded. Everything
  else about that message is complete.

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
  constraint would have silently prevented. Holds both body renderings and `truncated`; the rest of
  the message hangs off it in the three tables below, so that listing mail stays cheap.
- `email_headers` — one row per header *occurrence*, ordered by `ordinal`. Not a map column: names
  repeat and their order is meaningful.
- `email_attachments` — the attachment index. **No bytes**: `part_path` is the position of the part
  within the MIME tree (`0` is the message, `0.2.1` the first child of its second child) and the
  content is read back out of `email_raw_sources` on download. `part_index` is the stable 0-based
  number the URL uses.
- `email_raw_sources` — the complete message source as `bytea`, one row per message, absent for a
  `truncated` one. A table of its own rather than a column, so that a query returning 500 messages
  does not load 500 whole messages to render a list of subjects. **This is the table that grows**:
  see the sizing note in [`migrations/002-full-content.sql`](migrations/002-full-content.sql).
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

### Upgrading to full-content storage

[`migrations/002-full-content.sql`](migrations/002-full-content.sql) adds the columns and tables
above. **Unlike 001 it is optional** — it drops nothing, and `ddl-auto=update` creates all of it on
startup; the new `email_messages` columns are nullable precisely so that it can. Run it if you want
the cosmetic backfill or the schema created deliberately.

What no migration can do is **backfill existing mail**. Rows stored before this change keep the
plain-text body they were stored with and gain no headers, attachments or source, because that
content was discarded at ingestion and is not in the database to recover. Re-reading a mailbox means
rewinding its IMAP checkpoint *and* deleting that user's stored rows first — dedup on
`(user_id, message_id)` otherwise makes the poller skip every message it already has. That is
destructive and re-delivers everything to that user's subscribers under new ids; the migration file
spells out the statements and the consequences.

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
  "body": "Body text here.",
  "bodyHtml": "<p>Body <b>text</b> here.</p>",
  "headers": {
    "Received": ["from mx2.example.com …", "from mx1.example.com …"],
    "Message-ID": ["<abc@example.com>"],
    "Cc": ["carol@example.com"],
    "Reply-To": ["noreply@example.com"],
    "X-Campaign-Id": ["spring-2026"]
  },
  "attachments": [
    {
      "n": 0,
      "filename": "invoice.pdf",
      "mimeType": "application/pdf",
      "sizeBytes": 51234,
      "contentId": null,
      "inline": false,
      "available": true,
      "url": "/mails/42/attachments/0"
    }
  ],
  "truncated": false
}
```

`account` is the mailbox the message was polled from — taken from the `users` row, not from a
header, since mail can arrive by Bcc, alias, or forwarding and `to` is then not the owner. A
per-mailbox subscriber always sees the same value; a `GMAIL_WEBHOOK_CLIENTS` broadcast receiver
uses it to tell mailboxes apart.

`headers` carries **every** header the message had. Values are lists because names repeat, and the
order within a list is the order the message carried them. Lookup is case-insensitive: the key is
the first spelling seen, so a sender writing `CC` is still found under the name you ask for.

`attachments` is metadata plus a URL, never bytes — see [Attachments](#attachments) for why, and for
what `available: false` means. `truncated` says the message was above `GMAIL_MAX_MESSAGE_BYTES`; its
headers and text bodies are still complete.

> **Payloads are larger than they were.** A message that used to deliver as seven small fields now
> carries its HTML body and its full header block. If a receiver has a request size limit, check it
> before deploying: a rejected delivery leaves the subscription's cursor where it is and is retried
> forever, which stalls that receiver rather than losing the mail.

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
  "body": "Body text here.",
  "bodyHtml": "<p>Body <b>text</b> here.</p>",
  "headers": {"Cc": ["carol@example.com"], "X-Campaign-Id": ["spring-2026"]},
  "attachments": [
    {"n": 0, "filename": "invoice.pdf", "mimeType": "application/pdf", "sizeBytes": 51234,
     "contentId": null, "inline": false, "available": true, "url": "/mails/42/attachments/0"}
  ],
  "sizeBytes": 68219,
  "truncated": false
}
```

`sizeBytes` is the size of the whole original message, which is *not* the sum of the attachment
sizes — it includes headers, MIME framing and transfer encoding. It is measured from the stored
source, so it matches the archive exactly; only for a `truncated` message, where there is no
archive to measure, is it the size the IMAP server reported.

### One message by id

```http
GET /mails/{id}
```

The same object as above. `404` both for an id that does not exist and for one belonging to another
mailbox, so the route cannot be used to discover which ids are taken.

### Attachments

```http
GET /mails/{id}/attachments/{n}
```

The decoded bytes of one part, with its own `Content-Type` and a `Content-Disposition` filename.
`n` is the `n` from the message's `attachments` array, and the URL is given to you in `url` — follow
that rather than constructing it. Set `GMAIL_PUBLIC_BASE_URL` to have it emitted as an absolute URL;
without it, `url` is a path relative to this service's root.

Attachments are served **by reference, not inline**, in both the delivery payload and the read API.
Base64 inflates content by a third, and a single 25 MB attachment inlined into a webhook POST would
block that subscription's cursor behind one enormous request for as long as the receiver took to
accept it.

Statuses:

- `200` with the bytes.
- `404` — no such message for this caller, or no such `n` on it.
- `409` — the message is `truncated`: the part is real and described in the index, but its bytes
  were never stored. `available` is `false` on those refs, so a client can tell before asking. Raise
  `GMAIL_MAX_MESSAGE_BYTES` if this happens more than you expect — but note it applies only to mail
  polled after the change, since the source of an already-stored message is not recoverable.

Inline images (`inline: true`) are the parts an HTML body references as `src="cid:..."`; match the
`cid` against `contentId`, which is stored without its angle brackets.

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

Each message is downloaded once, in full, and everything stored is parsed from those bytes. A
message the server reports as larger than `GMAIL_MAX_MESSAGE_BYTES` is never downloaded at all: its
structure is read from the IMAP `BODYSTRUCTURE` the server already reports and its text bodies are
fetched as individual sections, so the cap bounds bandwidth as well as storage.
