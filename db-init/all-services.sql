-- Consolidated Postgres for the all-services stack.
--
-- One instance holds one database + one login role per app. The database,
-- role, and password names stay app-named (klippy/auth/secrets/gmail) to match the
-- convention used everywhere else. This is the single source of truth for the
-- repo's databases: every per-app compose file uses this same instance rather
-- than creating its own. It runs once, when the postgres data volume is first
-- initialised.
CREATE ROLE klippy  LOGIN PASSWORD 'klippy';
CREATE DATABASE klippy  OWNER klippy;

CREATE ROLE auth    LOGIN PASSWORD 'auth';
CREATE DATABASE auth    OWNER auth;

-- The secrets manager resolves roles by *which* auth deployment accepts a token,
-- so admin identities live in their own auth server, separate from client ones.
CREATE ROLE authadmin LOGIN PASSWORD 'authadmin';
CREATE DATABASE authadmin OWNER authadmin;

CREATE ROLE secrets LOGIN PASSWORD 'secrets';
CREATE DATABASE secrets OWNER secrets;

CREATE ROLE gmail   LOGIN PASSWORD 'gmail';
CREATE DATABASE gmail   OWNER gmail;

CREATE ROLE insta   LOGIN PASSWORD 'insta';
CREATE DATABASE insta   OWNER insta;

-- jarvis-detection's frame hub: the frame_events log clients replay from and the
-- frame_cursors positions of named subscriptions. frame_events holds JPEG bytes, so it
-- is sized by DETECTION_FRAME_RETENTION_SECONDS rather than growing indefinitely.
CREATE ROLE jarvis  LOGIN PASSWORD 'jarvis';
CREATE DATABASE jarvis  OWNER jarvis;
