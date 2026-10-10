-- The reverse proxy's audit trail: one row per request the proxy was asked to serve, whether it
-- forwarded it, refused it or failed on it.
--
-- Applied by JdbcAuditStore at startup, and idempotent so it can run on every boot. It is
-- deliberately plain DDL rather than a Hibernate mapping: this table exists to be *queried*, by
-- people and by tools that are not this application, so its shape and its indexes are written
-- down here rather than inferred from annotations.
--
-- The CREATE gives a fresh database the current shape; the ALTERs below bring a table created by
-- an older build up to it. Both are needed, and both are no-ops the second time.

CREATE TABLE IF NOT EXISTS request_audit (
    id             BIGSERIAL   PRIMARY KEY,
    -- When the proxy took the request, not when the row was written: the writer runs behind the
    -- request path, so insertion order is not arrival order and only this column is.
    received_at    TIMESTAMPTZ NOT NULL,
    -- PROXIED | BLOCKED | REJECTED | FAILED — see ProxyAuditRecord.Outcome.
    outcome        TEXT        NOT NULL,
    -- The X-Request-Id the caller arrived with, or one minted here. This is what joins a row to
    -- the same request's log lines, and to whatever the upstream logged about it.
    request_id     TEXT,
    -- The caller's own name, from the Service request header. NULL when the caller sent none,
    -- which is how "who is still unattributed" stays a query.
    service        TEXT,
    -- The authenticated identity, from X-Client-Id. The only "who" column here that means a
    -- credential rather than a machine — service is a process, the addresses are hops.
    client_id      TEXT,
    method         TEXT        NOT NULL,
    -- Split from the query on purpose: a path is what you group by, a query is what you search.
    path           TEXT        NOT NULL,
    query          TEXT,
    -- The socket address, which behind another hop is that hop; forwarded_for is what that hop
    -- claims the original was. Both are kept because neither can be trusted to be the other.
    remote_address TEXT,
    forwarded_for  TEXT,
    user_agent     TEXT,
    -- Which proxy wrote this. Constant per process and repeated on every row on purpose: two
    -- proxies sharing this table are only distinguishable by these two columns.
    upstream       TEXT,
    -- The policy names that were in force, comma-separated. Empty string means an open
    -- pass-through, which is not the same as not knowing what ran.
    policies       TEXT,
    status         INTEGER     NOT NULL,
    -- The policy that refused it. NULL unless outcome = 'BLOCKED'.
    policy         TEXT,
    -- Sizes and timing exist only for a request that actually reached the upstream.
    request_bytes  INTEGER,
    response_bytes INTEGER,
    -- What the client waited for, and what the origin took. The gap between them is this proxy's
    -- own cost, which is the only way to tell a slow upstream from a slow policy chain.
    duration_ms    BIGINT,
    upstream_ms    BIGINT,
    -- Why, when there is a why: the rejection reason (oversized body) or the upstream error.
    detail         TEXT
);

-- Upgrades a table created before these columns existed. Adding them nullable is what makes this
-- safe on a populated table: every one of them is a fact the proxy did not record at the time,
-- and NULL says exactly that.
ALTER TABLE request_audit ADD COLUMN IF NOT EXISTS request_id    TEXT;
ALTER TABLE request_audit ADD COLUMN IF NOT EXISTS client_id     TEXT;
ALTER TABLE request_audit ADD COLUMN IF NOT EXISTS forwarded_for TEXT;
ALTER TABLE request_audit ADD COLUMN IF NOT EXISTS user_agent    TEXT;
ALTER TABLE request_audit ADD COLUMN IF NOT EXISTS upstream      TEXT;
ALTER TABLE request_audit ADD COLUMN IF NOT EXISTS policies      TEXT;
ALTER TABLE request_audit ADD COLUMN IF NOT EXISTS upstream_ms   BIGINT;

-- "What happened recently", the query every other one starts from.
CREATE INDEX IF NOT EXISTS request_audit_received_at_idx
    ON request_audit (received_at DESC);

-- "What did service X do", the reason the Service header is collected at all. Partial: rows
-- without a service are exactly the ones this index would never serve.
CREATE INDEX IF NOT EXISTS request_audit_service_idx
    ON request_audit (service, received_at DESC)
    WHERE service IS NOT NULL;

-- "What did this identity do" — the same question asked about a credential rather than a process,
-- which is the one an audit is usually opened to answer. Partial for the same reason.
CREATE INDEX IF NOT EXISTS request_audit_client_id_idx
    ON request_audit (client_id, received_at DESC)
    WHERE client_id IS NOT NULL;

-- "Everything about that one request", starting from an id seen in a log line or an error page.
CREATE INDEX IF NOT EXISTS request_audit_request_id_idx
    ON request_audit (request_id)
    WHERE request_id IS NOT NULL;

-- "What got blocked / what failed" — the audit questions with a security answer. Partial for the
-- same reason: PROXIED is the overwhelming majority and is served by the received_at index.
CREATE INDEX IF NOT EXISTS request_audit_outcome_idx
    ON request_audit (outcome, received_at DESC)
    WHERE outcome <> 'PROXIED';
