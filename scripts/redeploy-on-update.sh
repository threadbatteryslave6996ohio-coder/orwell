#!/usr/bin/env bash
# Checks origin/main for new commits and, if the local checkout is behind,
# fast-forwards to it and redeploys the docker-compose stack.
#
#   ./scripts/redeploy-on-update.sh          # check once; redeploy if behind
#   ./scripts/redeploy-on-update.sh --force  # redeploy even if already up to date
#
# The all-services image builds the jars inside its multi-stage Dockerfile, so
# a redeploy is just `docker compose up -d --build` — no local mvn step needed.
#
# Intended to be run on a timer (cron/systemd) on the deploy host. Idempotent:
# when there is nothing new and --force is not given, it exits 0 without
# touching the running stack.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRANCH="${DEPLOY_BRANCH:-main}"
COMPOSE_FILE="$ROOT/docker-compose.all-services.yml"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

cd "$ROOT" || { echo "Cannot cd to repo root: $ROOT" >&2; exit 1; }

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
die() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*" >&2; exit 1; }

# --- Preconditions ------------------------------------------------------------
command -v git >/dev/null 2>&1 || die "git not found on PATH"
docker compose version >/dev/null 2>&1 || die "'docker compose' not available"
[ -f "$COMPOSE_FILE" ] || die "compose file not found: $COMPOSE_FILE"

current_branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$current_branch" = "$BRANCH" ] || die "checkout is on '$current_branch', expected '$BRANCH'"

# --- 1. Check for updates -----------------------------------------------------
log "Fetching origin/$BRANCH ..."
git fetch --quiet origin "$BRANCH" || die "git fetch failed"

local_sha="$(git rev-parse HEAD)"
remote_sha="$(git rev-parse "origin/$BRANCH")"

if [ "$local_sha" = "$remote_sha" ]; then
  if [ "$FORCE" = 0 ]; then
    log "Already up to date at ${local_sha:0:12}. Nothing to do."
    exit 0
  fi
  log "Already up to date at ${local_sha:0:12}, but --force given."
else
  # Refuse to clobber local commits: only proceed if we can fast-forward.
  base="$(git merge-base HEAD "origin/$BRANCH")"
  [ "$base" = "$local_sha" ] || die "local $BRANCH has diverged from origin; refusing to auto-deploy"

  log "Update available: ${local_sha:0:12} -> ${remote_sha:0:12}. Fast-forwarding ..."
  git merge --ff-only "origin/$BRANCH" || die "fast-forward merge failed"
fi

# --- 2. Redeploy --------------------------------------------------------------
log "Rebuilding and restarting the stack ..."
docker compose -f "$COMPOSE_FILE" up -d --build || die "docker compose up failed"

log "Pruning dangling images ..."
docker image prune -f >/dev/null 2>&1 || true

log "Redeploy complete at $(git rev-parse --short HEAD)."
