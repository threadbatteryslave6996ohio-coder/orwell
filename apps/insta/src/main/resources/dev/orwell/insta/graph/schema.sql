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

-- One row per follow relationship, in one direction: follower_id follows followee_id.
--
-- The edge holds the *current state* and the watermark; the *history* lives in `follows` and
-- `unfollows`, one row per event. That split is what makes a repeat offender visible: an account
-- that has left three times has three `unfollows` rows, where a single flag would show only the
-- most recent departure and a resurrected row would erase even that.
--
-- last_seen_at is the watermark that makes unfollows detectable at all. An unfollow is never an
-- event anyone observes — you learn it from an account being absent from a walk that saw
-- everything — so a complete walk deactivates whatever it did not refresh, and an incomplete one
-- deactivates nothing.
CREATE TABLE IF NOT EXISTS follow_edge (
    id            BIGSERIAL PRIMARY KEY,
    followee_id   TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    follower_id   TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT follow_edge_pair_key UNIQUE (followee_id, follower_id)
);

-- Migration, part one: reshape follow_edge itself. This has to happen before `follows` and
-- `unfollows` are created, because they carry a foreign key to follow_edge.id and on an older
-- database that column does not exist yet. Guarded on lost_at, which part two drops, so a fresh
-- database never enters either block.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'follow_edge' AND column_name = 'lost_at') THEN
        ALTER TABLE follow_edge ADD COLUMN IF NOT EXISTS id BIGSERIAL;
        ALTER TABLE follow_edge ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
        UPDATE follow_edge SET active = (lost_at IS NULL);

        ALTER TABLE follow_edge DROP CONSTRAINT IF EXISTS follow_edge_pkey;
        ALTER TABLE follow_edge ADD PRIMARY KEY (id);
        ALTER TABLE follow_edge ADD CONSTRAINT follow_edge_pair_key
            UNIQUE (followee_id, follower_id);
    END IF;
END $$;

-- Every time a follow began. One row on the first sighting, another on each return.
CREATE TABLE IF NOT EXISTS follows (
    id      BIGSERIAL PRIMARY KEY,
    edge_id BIGINT NOT NULL REFERENCES follow_edge(id) ON DELETE CASCADE,
    at      TIMESTAMPTZ NOT NULL
);

-- Every time one ended. notified_at lives here rather than on the edge because it belongs to a
-- single departure: each unfollow is its own row with its own stamp, so a later return cannot make
-- an unsent alert look already sent — the bug a flag on the edge had to be reset to avoid.
CREATE TABLE IF NOT EXISTS unfollows (
    id          BIGSERIAL PRIMARY KEY,
    edge_id     BIGINT NOT NULL REFERENCES follow_edge(id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL,
    notified_at TIMESTAMPTZ
);

-- Migration, part two: move the history the old columns held into the new tables, then drop them.
-- Dropping lost_at last is what makes the whole migration self-skipping on the next run.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'follow_edge' AND column_name = 'lost_at') THEN
        -- One follow per edge at its first sighting, and an unfollow for anyone currently departed.
        -- Earlier cycles are not recoverable: the old shape overwrote them, which is the reason
        -- this change exists.
        INSERT INTO follows (edge_id, at) SELECT id, first_seen_at FROM follow_edge;
        INSERT INTO unfollows (edge_id, at, notified_at)
            SELECT id, lost_at, unfollow_notified_at FROM follow_edge WHERE lost_at IS NOT NULL;

        ALTER TABLE follow_edge DROP COLUMN lost_at;
        ALTER TABLE follow_edge DROP COLUMN unfollow_notified_at;
    END IF;
END $$;

-- After the migration, never before it: on a database still carrying the old shape the `active`
-- column does not exist until the blocks above have run, and a partial index on a missing column
-- is an error rather than a no-op.
CREATE INDEX IF NOT EXISTS follow_edge_follower_idx ON follow_edge (follower_id);
CREATE INDEX IF NOT EXISTS follow_edge_active_idx ON follow_edge (followee_id) WHERE active;
CREATE INDEX IF NOT EXISTS follows_edge_idx ON follows (edge_id, at);
CREATE INDEX IF NOT EXISTS unfollows_edge_idx ON unfollows (edge_id, at);
-- The alert dispatcher's entire working set.
CREATE INDEX IF NOT EXISTS unfollows_unnotified_idx ON unfollows (at) WHERE notified_at IS NULL;

-- ─── posts ─────────────────────────────────────────────────────────────────────────────────────

-- Identity plus the facts that cannot change after publication.
--
-- deleted_at is deliberately never set by the profile-lookup path: `latestPosts` is only the 12
-- most recent posts, which is a truncated listing by definition, and inferring deletion from a
-- truncated listing would retire every post older than the newest twelve. It waits for a complete
-- listing from the dedicated post actor, under the same rule as follow_edge.lost_at.
CREATE TABLE IF NOT EXISTS post (
    id            TEXT PRIMARY KEY,
    account_id    TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    short_code    TEXT,
    taken_at      TIMESTAMPTZ,
    type          TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS post_account_idx ON post (account_id, taken_at DESC);
CREATE INDEX IF NOT EXISTS post_live_idx ON post (account_id) WHERE deleted_at IS NULL;

-- Captions are editable, so they are a value history like bios, digest-keyed for the same reason.
CREATE TABLE IF NOT EXISTS post_caption (
    post_id       TEXT NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    caption_hash  TEXT NOT NULL,
    caption       TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, caption_hash)
);

-- The one genuine time series here: likes and comments move on almost every observation, and the
-- curve is the point. A row is inserted only when a number actually changes; last_seen_at covers
-- the flat stretches, so a repeated sync of an unchanged post adds nothing.
CREATE TABLE IF NOT EXISTS post_metric (
    post_id          TEXT NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    likes_count      BIGINT,
    comments_count   BIGINT,
    video_view_count BIGINT,
    first_seen_at    TIMESTAMPTZ NOT NULL,
    last_seen_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, first_seen_at)
);
CREATE INDEX IF NOT EXISTS post_metric_latest_idx ON post_metric (post_id, last_seen_at DESC);

-- Same content-hash-plus-bucket design as profile pictures, so it reuses PictureStore unchanged.
-- position orders the items of a carousel.
CREATE TABLE IF NOT EXISTS post_media (
    post_id       TEXT NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    content_hash  TEXT NOT NULL,
    bucket_key    TEXT NOT NULL,
    source_url    TEXT,
    position      INT,
    byte_size     INT,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, content_hash)
);
CREATE INDEX IF NOT EXISTS post_media_hash_idx ON post_media (content_hash);
