-- Consolidated Postgres for the all-services stack.
--
-- One instance holds one database + one login role per app. The database,
-- role, and password names stay app-named (klippy/auth/secrets) to match the
-- convention used everywhere else and by scripts/dev-stack.sh's orw-pg. This
-- runs once, when the postgres data volume is first initialised.
CREATE ROLE klippy  LOGIN PASSWORD 'klippy';
CREATE DATABASE klippy  OWNER klippy;

CREATE ROLE auth    LOGIN PASSWORD 'auth';
CREATE DATABASE auth    OWNER auth;

CREATE ROLE secrets LOGIN PASSWORD 'secrets';
CREATE DATABASE secrets OWNER secrets;
