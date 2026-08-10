-- gmail-general: store the whole message, not a four-field summary.
--
-- Adds the HTML body, every header, an attachment index, and the raw RFC 822 source.
--
-- UNLIKE 001, THIS MIGRATION IS OPTIONAL. Nothing here drops a constraint or a key, and
-- `spring.jpa.hibernate.ddl-auto=update` adds every table, column and index below on its own. The
-- new columns on `email_messages` are deliberately declared NULLABLE in the entity for exactly that
-- reason: Postgres cannot add a NOT NULL column to a table that already has rows without a default,
-- Hibernate logs that failure as a warning rather than refusing to start, and the result would be a
-- service whose every insert fails silently. Nullable columns can always be added, and the entity
-- coalesces on read, so an old row behaves as a message with no HTML part and no archive.
--
-- Run it anyway if you want the backfill in step 2 (cosmetic) or prefer the schema created
-- deliberately rather than inferred. It is idempotent and safe to run against a live database.
--
-- WHAT THIS DOES NOT DO: existing mail is not re-fetched. Rows stored before this change keep the
-- plain-text body they were stored with and gain no headers, attachments or source — that content
-- was discarded at ingestion and is not recoverable from the database. Only mail polled after the
-- new jar starts is stored in full. To backfill a mailbox you must rewind its IMAP checkpoint and
-- let the poller re-read it:
--
--   UPDATE imap_checkpoints SET last_uid = 0 WHERE user_id = <id> AND folder = 'INBOX';
--
-- Re-fetched messages are deduped on (user_id, message_id), so the rewind will NOT restore content
-- to rows that already exist — the poller will skip every one of them. To actually replace them,
-- delete that user's stored mail first (this is destructive; the deletes cascade in the order
-- shown because of the foreign keys):
--
--   DELETE FROM email_raw_sources WHERE message_id IN (SELECT id FROM email_messages WHERE user_id = <id>);
--   DELETE FROM email_attachments WHERE message_id IN (SELECT id FROM email_messages WHERE user_id = <id>);
--   DELETE FROM email_headers     WHERE message_id IN (SELECT id FROM email_messages WHERE user_id = <id>);
--   DELETE FROM email_messages    WHERE user_id = <id>;
--
-- Note that this re-issues every message to that user's webhook subscribers under new ids, because
-- the delivery cursor is an `email_messages.id`. Reset the cursors too if that is not wanted.

BEGIN;

-- 1. The message row gains the HTML body and two facts about the archive.
--    `truncated` marks a message stored above GMAIL_MAX_MESSAGE_BYTES: its headers, text bodies and
--    attachment index are complete, but it has no row in email_raw_sources and its attachment bytes
--    are therefore not retrievable.
ALTER TABLE email_messages ADD COLUMN IF NOT EXISTS body_html      text;
ALTER TABLE email_messages ADD COLUMN IF NOT EXISTS raw_size_bytes bigint;
ALTER TABLE email_messages ADD COLUMN IF NOT EXISTS truncated      boolean;

-- 2. Cosmetic backfill: make old rows read as "no HTML part, nothing truncated" in the database as
--    well as through the API, which coalesces these anyway.
UPDATE email_messages SET body_html = ''    WHERE body_html IS NULL;
UPDATE email_messages SET truncated = false WHERE truncated IS NULL;
UPDATE email_messages SET raw_size_bytes = 0 WHERE raw_size_bytes IS NULL;

-- 3. Headers: one row per occurrence, not a map column. Header names repeat and their order is
--    meaningful — the Received chain is a delivery path read bottom-up, and a map would keep only
--    the last hop. `ordinal` preserves the order the message carried them in.
CREATE TABLE IF NOT EXISTS email_headers (
    id         bigserial    PRIMARY KEY,
    message_id bigint       NOT NULL REFERENCES email_messages (id),
    ordinal    integer      NOT NULL,
    name       varchar(255) NOT NULL,
    value      text         NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_email_headers_message_id ON email_headers (message_id);

-- 4. Attachments: an index, not a store. No bytes here — `part_path` is the position of the part
--    within the MIME tree ("0" is the message, "0.2.1" the first child of its second child), and
--    the content is read back out of email_raw_sources at download time. Storing the decoded bytes
--    here as well would double what every message with an attachment costs to keep.
--    `part_index` is the stable 0-based number the download URL uses.
CREATE TABLE IF NOT EXISTS email_attachments (
    id         bigserial    PRIMARY KEY,
    message_id bigint       NOT NULL REFERENCES email_messages (id),
    part_index integer      NOT NULL,
    part_path  varchar(128) NOT NULL,
    filename   text,
    mime_type  varchar(255) NOT NULL,
    size_bytes bigint       NOT NULL,
    content_id varchar(998),
    inline     boolean      NOT NULL,
    CONSTRAINT uq_email_attachments_message_part UNIQUE (message_id, part_index)
);

CREATE INDEX IF NOT EXISTS idx_email_attachments_message_id ON email_attachments (message_id);

-- 5. The archive. `bytea`, not a large object: an OID column would put the content outside the
--    table with its own lifecycle to manage and nothing in a plain row-level dump.
--
--    A table of its own rather than a column on email_messages because it is by far the largest
--    thing stored per message. As a column it would be read by every query returning a message —
--    GET /mails?limit=500 would pull up to 500 whole messages into memory to list their subjects.
--
--    PLAN FOR THIS TABLE'S SIZE. It is the whole of every message polled from now on. Postgres
--    TOASTs and compresses these values out of line, so text-only mail costs little, but a mailbox
--    receiving attachments will make this table dominate the gmail database. Lower
--    GMAIL_MAX_MESSAGE_BYTES to cap per-message cost, and prune rows here — and only here — to
--    reclaim space while keeping every message readable minus its attachment bytes. Mark what you
--    prune, or the API keeps advertising those attachments as fetchable and answers 409 instead:
--
--      UPDATE email_messages SET truncated = true WHERE id IN (<pruned ids>);
--      DELETE FROM email_raw_sources WHERE message_id IN (<pruned ids>);
CREATE TABLE IF NOT EXISTS email_raw_sources (
    id         bigserial PRIMARY KEY,
    message_id bigint    NOT NULL UNIQUE REFERENCES email_messages (id),
    content    bytea     NOT NULL
);

COMMIT;
