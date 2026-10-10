-- Consolidated Postgres for the all-services stack.
--
-- One instance holds one database + one login role per app. The database,
-- role, and password names stay app-named (klippy/auth/secrets/gmail) to match the
-- convention used everywhere else. This is the single source of truth for the
-- repo's databases: every per-app compose file uses this same instance rather
-- than creating its own.
--
-- POSTGRES RUNS THIS ONLY ONCE, when the data volume is first initialised. That is the trap every
-- entry added here after the first deployment falls into: the role exists on a fresh checkout and
-- nowhere else, so an existing stack upgrades into a service that cannot connect — and because the
-- applications gate on `depends_on: service_healthy`, one missing role can stop the whole stack
-- coming up rather than just its own service.
--
-- So every statement below is written to be **re-runnable**, and repairing an older volume is:
--
--   docker compose -f docker-compose.storages.yml exec -T db \
--       psql -U postgres -v ON_ERROR_STOP=1 < db-init/all-services.sql
--
-- Roles are guarded with a DO block; databases with psql's \gexec, since CREATE DATABASE cannot
-- run inside one. Passwords are not reset on a re-run — an operator who changed one keeps it.
DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'klippy') THEN
    CREATE ROLE klippy LOGIN PASSWORD 'klippy';
END IF; END $$;
SELECT 'CREATE DATABASE klippy OWNER klippy'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'klippy')\gexec

DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auth') THEN
    CREATE ROLE auth LOGIN PASSWORD 'auth';
END IF; END $$;
SELECT 'CREATE DATABASE auth OWNER auth'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'auth')\gexec

-- The secrets manager resolves roles by *which* auth deployment accepts a token,
-- so admin identities live in their own auth server, separate from client ones.
DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authadmin') THEN
    CREATE ROLE authadmin LOGIN PASSWORD 'authadmin';
END IF; END $$;
SELECT 'CREATE DATABASE authadmin OWNER authadmin'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'authadmin')\gexec

DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'secrets') THEN
    CREATE ROLE secrets LOGIN PASSWORD 'secrets';
END IF; END $$;
SELECT 'CREATE DATABASE secrets OWNER secrets'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'secrets')\gexec

DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'gmail') THEN
    CREATE ROLE gmail LOGIN PASSWORD 'gmail';
END IF; END $$;
SELECT 'CREATE DATABASE gmail OWNER gmail'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'gmail')\gexec

DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'insta') THEN
    CREATE ROLE insta LOGIN PASSWORD 'insta';
END IF; END $$;
SELECT 'CREATE DATABASE insta OWNER insta'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'insta')\gexec

-- jarvis-hub: the frame_events log clients replay from and the
-- frame_cursors positions of named subscriptions. frame_events holds JPEG bytes, so it
-- is bounded by jarvis-retention-worker (RETENTION_FRAME_MAX_BYTES / RETENTION_FRAME_MAX_AGE_SECONDS)
-- rather than growing indefinitely.
DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jarvis') THEN
    CREATE ROLE jarvis LOGIN PASSWORD 'jarvis';
END IF; END $$;
SELECT 'CREATE DATABASE jarvis OWNER jarvis'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'jarvis')\gexec

-- reverse-proxy: the request_audit trail, one row per request the proxy was asked to serve. The
-- proxy applies its own schema at startup (apps/reverse-proxy/.../audit-schema.sql). Bounded by
-- PROXY_AUDIT_RETENTION_DAYS, swept in-process — the rows are small enough not to need a worker
-- of their own, unlike jarvis's frame bytes.
DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'proxy') THEN
    CREATE ROLE proxy LOGIN PASSWORD 'proxy';
END IF; END $$;
SELECT 'CREATE DATABASE proxy OWNER proxy'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'proxy')\gexec
