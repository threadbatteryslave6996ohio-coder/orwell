#!/usr/bin/env bash
# Runs docker compose against both of the repo's compose files.
#
#   ./scripts/stack.sh                       # up -d, the whole stack
#   ./scripts/stack.sh up -d --build         # anything else is passed straight through
#   ./scripts/stack.sh logs -f auth-server
#   ./scripts/stack.sh ps
#   ./scripts/stack.sh down
#   ./scripts/stack.sh --storages-only up -d # Postgres, Redis and MinIO, no applications
#
# The stack is split in two: docker-compose.storages.yml holds Postgres, Redis and MinIO,
# docker-compose.all-services.yml holds the applications. Both declare the same project, so
# Compose merges them — but *every* subcommand needs both, not just `up`: the applications
# `depends_on` `db` and `redis`, so `ps`, `logs` and `down` against the applications file alone
# are refused with "depends on undefined service". That is easy to forget, which is what this
# script is for. Nothing here is magic; it only supplies the two -f flags.
#
# Works from any directory: the compose files are resolved from this script's location, not the
# caller's cwd, so relative paths inside them (the db-init mount, the build contexts) stay correct.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORAGES="$ROOT/docker-compose.storages.yml"
SERVICES="$ROOT/docker-compose.all-services.yml"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
docker compose version >/dev/null 2>&1 || die "'docker compose' not available (needs Compose v2)"
[ -f "$STORAGES" ] || die "compose file not found: $STORAGES"

files=(-f "$STORAGES")
if [ "${1:-}" = "--storages-only" ]; then
    shift
else
    [ -f "$SERVICES" ] || die "compose file not found: $SERVICES"
    files+=(-f "$SERVICES")
fi

# No subcommand means the thing this is usually run for. Anything else is the caller's to choose,
# passed through untouched so `--build`, `--remove-orphans`, a service name and so on all work.
if [ "$#" -eq 0 ]; then
    set -- up -d
fi

# Run from the repo root: Compose resolves a file's relative paths against the file, but its own
# default .env lookup is cwd-based, and the root .env is where ORWELL_HTTP_PORT and friends live.
cd "$ROOT"

echo "+ docker compose ${files[*]} $*" >&2
exec docker compose "${files[@]}" "$@"
