-- The follow graph and the account facts that change over time.
--
-- Applied idempotently at the start of every `insta sync`, so there is no migration tool and no
-- ordering to keep straight: this file is the schema, and running it twice is a no-op.
--
-- Identity is Instagram's own account id, verbatim, as TEXT. It is stable across renames, which is
-- why usernames live in their own table rather than on the account; and it is TEXT rather than INT
-- because real ids already exceed INT (4014759590), and they are opaque values nobody does
-- arithmetic on.

CREATE TABLE IF NOT EXISTS account (
    id    TEXT PRIMARY KEY,
    added TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Handles change, and a released handle can be taken by someone else — so uniqueness is per
-- account, never global.
CREATE TABLE IF NOT EXISTS account_username (
    account_id    TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    username      TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, username)
);
CREATE INDEX IF NOT EXISTS account_username_lower_idx ON account_username (lower(username));

-- Keyed on a digest rather than the text: a bio is user-supplied and unbounded, and an oversized
-- value would fail the primary key index at insert time instead of just being stored.
-- No row means "we have not seen a bio"; an empty string means "we saw one and it was empty".
CREATE TABLE IF NOT EXISTS account_bio (
    account_id    TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    bio_hash      TEXT NOT NULL,
    bio           TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, bio_hash)
);

-- Keyed on the hash of the image bytes, not the URL: Instagram's CDN links are signed and change
-- on every scrape even when the picture has not, so a URL key would record a fresh "new picture"
-- daily and re-upload identical bytes forever. The hash also dedupes the default avatar, which a
-- large share of accounts share, down to one object.
CREATE TABLE IF NOT EXISTS account_profile_picture (
    account_id    TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    content_hash  TEXT NOT NULL,
    bucket_key    TEXT NOT NULL,
    source_url    TEXT,
    byte_size     INT,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, content_hash)
);
CREATE INDEX IF NOT EXISTS account_profile_picture_hash_idx
    ON account_profile_picture (content_hash);

-- One row per follow, in one direction: follower_id follows followee_id.
--
-- last_seen_at is the watermark that makes unfollows detectable at all. An unfollow is never an
-- event anyone observes — you learn it from an account being absent from a walk that saw
-- everything — so a complete walk retires whatever it did not refresh, and an incomplete one
-- retires nothing.
CREATE TABLE IF NOT EXISTS follow_edge (
    followee_id          TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    follower_id          TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    first_seen_at        TIMESTAMPTZ NOT NULL,
    last_seen_at         TIMESTAMPTZ NOT NULL,
    lost_at              TIMESTAMPTZ,
    unfollow_notified_at TIMESTAMPTZ,
    PRIMARY KEY (followee_id, follower_id)
);
CREATE INDEX IF NOT EXISTS follow_edge_follower_idx ON follow_edge (follower_id);
-- The alert dispatcher's entire working set.
CREATE INDEX IF NOT EXISTS follow_edge_unnotified_idx ON follow_edge (lost_at)
    WHERE lost_at IS NOT NULL AND unfollow_notified_at IS NULL;
