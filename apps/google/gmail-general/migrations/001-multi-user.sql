-- gmail-general: single mailbox -> multiple user-owned mailboxes.
--
-- Run this against the `gmail` database BEFORE starting the new jar.
--
-- Why this is not automatic: there is no Flyway or Liquibase in this repo, and
-- `spring.jpa.hibernate.ddl-auto=update` only ever ADDS tables, columns and indexes. It will not
-- drop the old primary key on `imap_checkpoints(folder)` nor the old global unique index on
-- `email_messages(message_id)`. Left in place, both survive the upgrade and break multi-user
-- silently rather than loudly:
--
--   * the old `message_id` unique index still rejects the second copy of one mail addressed to two
--     mailboxes, so the second user just never receives it;
--   * the old `folder` primary key still allows one checkpoint row per folder name, so two users
--     polling INBOX overwrite each other's UID cursor and each skips what the other consumed.
--
-- Steps 1 and 4 are safe to run as-is. Step 3 is the one that needs a decision from you, and step
-- 5 will fail loudly until you have made it — deliberately, so that no mail is dropped silently.

BEGIN;

-- 1. Hibernate creates `users` and `secrets` on startup, but creating them here keeps the whole
--    shape in one place and lets you run the backfill in step 3 before the app ever starts.
CREATE TABLE IF NOT EXISTS users (
    id         bigserial    PRIMARY KEY,
    email      varchar(320) NOT NULL UNIQUE,
    client_id  varchar(255) NOT NULL UNIQUE,
    created_at timestamptz  NOT NULL
);

CREATE TABLE IF NOT EXISTS secrets (
    id            bigserial   PRIMARY KEY,
    user_id       bigint      NOT NULL UNIQUE REFERENCES users (id),
    imap_password text        NOT NULL,
    updated_at    timestamptz NOT NULL
);

--    Webhook subscriptions: which URLs receive a given user's mail. Fan-out reads this table, so
--    an empty table means no mail is pushed anywhere (the pull API is unaffected). The unique
--    constraint is on (user_id, url) rather than url alone: two users may point at the same
--    receiver, and each needs its own row.
--    `last_delivered_id` is the per-subscription delivery cursor: the highest `email_messages.id`
--    that receiver has acknowledged. Delivery walks forward from it, so a receiver that was down
--    catches up rather than losing mail. Set it to the mailbox's current head when adding a
--    subscription by hand, or 0 to replay everything stored for that user.
CREATE TABLE IF NOT EXISTS webhook_subscriptions (
    id                bigserial     PRIMARY KEY,
    user_id           bigint        NOT NULL REFERENCES users (id),
    url               varchar(2048) NOT NULL,
    active            boolean       NOT NULL DEFAULT true,
    last_delivered_id bigint        NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL,
    CONSTRAINT uq_webhook_subscriptions_user_url UNIQUE (user_id, url)
);

CREATE INDEX IF NOT EXISTS idx_webhook_subscriptions_user_id
    ON webhook_subscriptions (user_id);

-- 2. Add the ownership columns as NULLABLE, so existing rows survive until step 3 assigns them.
ALTER TABLE email_messages   ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE imap_checkpoints ADD COLUMN IF NOT EXISTS user_id bigint;

-- 3. YOUR DECISION. Existing rows have no owner, because until now the mailbox lived in
--    IMAP_USERNAME rather than in the database. Pick one:
--
--    (a) Keep the existing mail — register the mailbox that produced it and adopt its rows.
--        Replace the three values, uncomment, and run:
--
--    INSERT INTO users (email, client_id, created_at)
--    VALUES ('you@gmail.com', 'your-consumer-client-id', now());
--
--    INSERT INTO secrets (user_id, imap_password, updated_at)
--    SELECT id, 'your-imap-app-password', now() FROM users WHERE email = 'you@gmail.com';
--
--    UPDATE email_messages   SET user_id = (SELECT id FROM users WHERE email = 'you@gmail.com')
--     WHERE user_id IS NULL;
--    UPDATE imap_checkpoints SET user_id = (SELECT id FROM users WHERE email = 'you@gmail.com')
--     WHERE user_id IS NULL;
--
--    (b) Discard the existing mail and start clean. This deletes stored mail permanently:
--
--    DELETE FROM email_messages;
--    DELETE FROM imap_checkpoints;

-- 4. Drop the constraints that are wrong for more than one mailbox.
--    Looked up rather than named: Postgres would have called the old unique index
--    `email_messages_message_id_key`, but Hibernate created it and names generated constraints
--    `UK_<hash>`. Dropping by literal name would silently no-op against the name it does not have,
--    leaving the constraint in place — the exact silent failure this migration exists to prevent.
DO $$
DECLARE
    target text;
BEGIN
    SELECT c.conname INTO target
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'email_messages'
       AND c.contype = 'u'
       AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                              WHERE a.attrelid = t.oid AND a.attname = 'message_id')]::smallint[];
    IF target IS NOT NULL THEN
        EXECUTE format('ALTER TABLE email_messages DROP CONSTRAINT %I', target);
    END IF;

    SELECT c.conname INTO target
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'imap_checkpoints'
       AND c.contype = 'p';
    IF target IS NOT NULL THEN
        EXECUTE format('ALTER TABLE imap_checkpoints DROP CONSTRAINT %I', target);
    END IF;
END $$;

ALTER TABLE imap_checkpoints ADD COLUMN IF NOT EXISTS id bigserial;

-- 5. Enforce ownership and the per-user constraints. These fail if step 3 was skipped, which is
--    the point: a NULL user_id here would mean mail nobody can read.
ALTER TABLE email_messages   ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE imap_checkpoints ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE imap_checkpoints ADD CONSTRAINT imap_checkpoints_pkey PRIMARY KEY (id);

ALTER TABLE email_messages
    ADD CONSTRAINT uq_email_messages_user_message UNIQUE (user_id, message_id);
ALTER TABLE imap_checkpoints
    ADD CONSTRAINT uq_imap_checkpoints_user_folder UNIQUE (user_id, folder);

ALTER TABLE email_messages
    ADD CONSTRAINT fk_email_messages_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE imap_checkpoints
    ADD CONSTRAINT fk_imap_checkpoints_user FOREIGN KEY (user_id) REFERENCES users (id);

CREATE INDEX IF NOT EXISTS idx_email_messages_user_id ON email_messages (user_id);

-- 6. OPTIONAL — move the old broadcast receivers onto per-mailbox subscriptions.
--    `GMAIL_WEBHOOK_CLIENTS` still works and still sends every user's mail to every listed URL.
--    Subscribing them per mailbox instead is what makes fan-out respect the same isolation the
--    read API has. Once each URL below is subscribed to the mailboxes it should actually receive,
--    unset `GMAIL_WEBHOOK_CLIENTS` — a URL that is both broadcast and subscribed is delivered
--    once, so the two can overlap safely while you migrate.
--
--    The cursor starts at the mailbox's current head so the receiver is not sent the entire
--    stored history on its first round. Use 0 instead if you do want that replay.
--
--    INSERT INTO webhook_subscriptions (user_id, url, active, last_delivered_id, created_at)
--    SELECT u.id, 'https://receiver.example.com/mail', true,
--           COALESCE((SELECT max(m.id) FROM email_messages m WHERE m.user_id = u.id), 0), now()
--      FROM users u WHERE u.email = 'you@gmail.com';

COMMIT;
