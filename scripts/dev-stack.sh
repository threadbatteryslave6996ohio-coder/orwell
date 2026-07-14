#!/usr/bin/env bash
# Brings up the full local dev stack for the orwell monorepo and a tmux session
# with one window per Linux client. Idempotent: safe to re-run.
#
#   ./scripts/dev-stack.sh          # start everything + create tmux session 'orw-clients'
#   ./scripts/dev-stack.sh down     # stop servers + containers + tmux session
#
# Each server runs standalone in its own JVM with its own .env under .dev-stack/<app>/.
# After it runs:  tmux attach -t orw-clients
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRATCH="${SCRATCH:-$ROOT/.dev-stack}"
SESSION="orw-clients"
VERSION="0.1.0-SNAPSHOT"

# Server ports
AUTH_PORT=8081
KLIPPY_PORT=8082
SECRETS_PORT=8083
PROXY_PORT=8084
KEEBOARDER_PORT=8025

AUTH_BASE="http://localhost:$AUTH_PORT"

# Client credentials (registered against the auth server below).
CLIP_ID="linux-clip";  CLIP_SECRET="clip-secret-123"
KEEB_ID="linux-keeb";  KEEB_SECRET="keeb-secret-123"

# name : jar-path pairs for every server we manage
declare -A JARS=(
  [auth]="$ROOT/apps/auth/http-based/server/target/auth-http-server-$VERSION-exec.jar"
  [klippy]="$ROOT/apps/klippy/server/target/clippy-server-$VERSION-exec.jar"
  [secrets]="$ROOT/apps/secrets-manager/server/target/secrets-server-$VERSION-exec.jar"
  [proxy]="$ROOT/apps/jarvis/bucket/proxy/target/bucket-proxy-$VERSION-exec.jar"
  [keeboarder]="$ROOT/apps/keeboarder/server/target/websocket-redis-server-$VERSION-exec.jar"
)

mkdir -p "$SCRATCH"

down() {
  echo "Stopping tmux session, servers, and containers..."
  tmux kill-session -t "$SESSION" 2>/dev/null || true
  for name in "${!JARS[@]}"; do
    pkill -f "$(basename "${JARS[$name]}")" 2>/dev/null || true
  done
  pkill -f "clippy-file-locker-.*-exec.jar" 2>/dev/null || true
  docker rm -f orw-pg orw-redis >/dev/null 2>&1 || true
  echo "Down."
}
[ "${1:-}" = "down" ] && { down; exit 0; }

missing=0
for name in "${!JARS[@]}"; do
  [ -f "${JARS[$name]}" ] || { echo "Missing $name jar: ${JARS[$name]}"; missing=1; }
done
[ "$missing" = 0 ] || { echo "Build first:  mvn -o -DskipTests package"; exit 1; }

# --- 1. Infra: one Postgres (auth/clippy/secrets DBs) + Redis -----------------
echo "==> Postgres + Redis"
# Start if stopped, create if missing, leave alone if already running.
ensure_container() {
  name="$1"; shift
  state=$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || echo missing)
  case "$state" in
    true)    echo "    $name already running" ;;
    false)   echo "    $name starting existing"; docker start "$name" >/dev/null ;;
    missing) echo "    $name creating"; docker run -d --name "$name" "$@" >/dev/null ;;
  esac
}
ensure_container orw-pg    -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16-alpine
ensure_container orw-redis -p 6379:6379 redis:7-alpine

echo -n "    waiting for postgres"
for _ in $(seq 1 30); do docker exec orw-pg pg_isready -U postgres >/dev/null 2>&1 && break; echo -n .; sleep 1; done; echo
for db in auth clippy secrets; do
  docker exec orw-pg psql -U postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='$db'"    | grep -q 1 || docker exec orw-pg psql -U postgres -c "CREATE ROLE $db LOGIN PASSWORD '$db';" >/dev/null
  docker exec orw-pg psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 || docker exec orw-pg psql -U postgres -c "CREATE DATABASE $db OWNER $db;"        >/dev/null
done

# --- 2. Per-server env files ---------------------------------------------------
write_env() { # write_env <app> <<'ENV' ... ENV
  mkdir -p "$SCRATCH/$1/logs"
  cat > "$SCRATCH/$1/.env"
}

write_env auth <<ENV
AUTH_DATASOURCE_URL=jdbc:postgresql://localhost:5432/auth
AUTH_DATASOURCE_USERNAME=auth
AUTH_DATASOURCE_PASSWORD=auth
AUTH_SERVER_PORT=$AUTH_PORT
AUTH_LOGGING_FILE_NAME=logs/auth-server.log
AUTH_JPA_HIBERNATE_DDL_AUTO=update
AUTH_JPA_JDBC_TIME_ZONE=UTC
ENV

write_env klippy <<ENV
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/clippy
SPRING_DATASOURCE_USERNAME=clippy
SPRING_DATASOURCE_PASSWORD=clippy
SERVER_PORT=$KLIPPY_PORT
CLIPPY_AUTH_BASE_URL=$AUTH_BASE
LOGGING_FILE_NAME=logs/clippy-server.log
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE=UTC
ENV

write_env secrets <<ENV
SECRETS_DATASOURCE_URL=jdbc:postgresql://localhost:5432/secrets
SECRETS_DATASOURCE_USERNAME=secrets
SECRETS_DATASOURCE_PASSWORD=secrets
SECRETS_SERVER_PORT=$SECRETS_PORT
SECRETS_LOGGING_FILE_NAME=logs/secrets-manager.log
SECRETS_JPA_HIBERNATE_DDL_AUTO=update
SECRETS_JPA_JDBC_TIME_ZONE=UTC
SECRETS_AUTH_BASE_URL=$AUTH_BASE
ENV

write_env proxy <<ENV
SERVER_PORT=$PROXY_PORT
PROXY_STORAGE_PROVIDER=aws
PROXY_S3_BUCKET_NAME=local-bucket
PROXY_S3_REGION=us-east-1
PROXY_S3_ENDPOINT=http://localhost:1
PROXY_S3_PATH_STYLE_ACCESS=true
PROXY_AUTH_SERVER_BASE_URL=$AUTH_BASE
PROXY_MANAGEMENT_USERNAME=admin
PROXY_MANAGEMENT_PASSWORD=change-me
PROXY_MANAGEMENT_SESSION_SECRET=0123456789012345678901234567890123
PROXY_LOGGING_AUDIT_FILE=logs/proxy-audit.log
ENV

write_env keeboarder <<ENV
HTTP_HOST=0.0.0.0
HTTP_PORT=$KEEBOARDER_PORT
WEBSOCKET_ENABLED=true
WEBSOCKET_CONTEXT_PATH=/ws
REDIS_HOST=localhost
REDIS_PORT=6379
CLIPPY_AUTH_BASE_URL=$AUTH_BASE
KEEBOARDER_SERVER_ROUTE_PREFIX=/api
ENV

# --- 3. Start servers (each in its own dir so it picks up its .env) -----------
start_server() { # start_server <app> <port> [extra env...]
  app="$1"; port="$2"; shift 2
  if curl -s -m 3 -o /dev/null "http://localhost:$port/health"; then
    echo "    $app already running on :$port"
    return
  fi
  ( cd "$SCRATCH/$app" && env "$@" nohup java -jar "${JARS[$app]}" > run.log 2>&1 & )
  echo -n "    $app starting on :$port"
  for _ in $(seq 1 60); do curl -s -m 3 -o /dev/null "http://localhost:$port/health" && break; echo -n .; sleep 2; done; echo
  curl -s -m 5 -o /dev/null "http://localhost:$port/health" \
    || { echo "    $app did not come up; see $SCRATCH/$app/run.log"; exit 1; }
}

echo "==> Servers"
start_server auth       "$AUTH_PORT"
start_server klippy     "$KLIPPY_PORT"
start_server secrets    "$SECRETS_PORT"
start_server proxy      "$PROXY_PORT" AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
start_server keeboarder "$KEEBOARDER_PORT"

# --- 4. Register client identities (idempotent; 409 if they already exist) ----
echo "==> Registering client identities"
for pair in "$CLIP_ID:$CLIP_SECRET" "$KEEB_ID:$KEEB_SECRET"; do
  id="${pair%%:*}"; secret="${pair#*:}"
  code=$(curl -s -m 8 -o /dev/null -w "%{http_code}" -X POST "$AUTH_BASE/identities" \
    -H "Content-Type: application/json" -d "{\"clientId\":\"$id\",\"secret\":\"$secret\"}")
  echo "    $id -> HTTP $code"
done

# --- 5. klippy client .env ----------------------------------------------------
cat > "$ROOT/apps/klippy/.env" <<ENV
REMOTE_SERVER_URL=http://localhost:$KLIPPY_PORT
AUTH_SERVER_URL=$AUTH_BASE
CLIENT_ID=$CLIP_ID
CLIENT_SECRET=$CLIP_SECRET
CLIPBOARD_BACKEND=wl-paste
CLIPBOARD_POLL_INTERVAL_MS=1000
ENV

# --- 6. tmux session: one window per client ----------------------------------
echo "==> tmux session '$SESSION'"
tmux kill-session -t "$SESSION" 2>/dev/null || true

tmux new-session -d -s "$SESSION" -n file-locker -c "$ROOT"
tmux send-keys -t "$SESSION:file-locker" \
  "java -jar apps/klippy/clients/file-locker/target/clippy-file-locker-$VERSION-exec.jar" C-m

tmux new-window -t "$SESSION" -n clip-client -c "$ROOT/apps/klippy"
tmux send-keys -t "$SESSION:clip-client" \
  "echo '[clip-client] needs a graphical session (wl-paste/xclip). Press Enter to start:'" C-m
tmux send-keys -t "$SESSION:clip-client" \
  "java -jar clients/linux/target/clippy-linux-client-$VERSION.jar"

tmux new-window -t "$SESSION" -n keeboarder -c "$ROOT"
tmux send-keys -t "$SESSION:keeboarder" \
  "export KEEBOARDER_SERVER_URL=ws://localhost:$KEEBOARDER_PORT/ws/chat KEEBOARDER_AUTH_BASE_URL=$AUTH_BASE KEEBOARDER_CLIENT_ID=$KEEB_ID KEEBOARDER_CLIENT_SECRET=$KEEB_SECRET KEEBOARDER_CLIENT_NAME=My-Linux" C-m
tmux send-keys -t "$SESSION:keeboarder" \
  "echo '[keeboarder] X11 ONLY; needs DISPLAY. Press Enter to start:'" C-m
tmux send-keys -t "$SESSION:keeboarder" \
  "java -jar apps/keeboarder/clients/linux/target/keeboarder-linux-client-$VERSION.jar"

tmux new-window -t "$SESSION" -n jarvis -c "$ROOT/apps/jarvis/clients/linux"
tmux send-keys -t "$SESSION:jarvis" \
  "echo '[jarvis] edit config.sh (REMOTE_SERVER_URL=http://localhost:$PROXY_PORT, PROXY_USERNAME=admin, PROXY_PASSWORD=change-me) then: ./start_recorder.sh'" C-m

tmux select-window -t "$SESSION:file-locker"

echo
echo "Stack up."
echo "  auth:       $AUTH_BASE"
echo "  klippy:     http://localhost:$KLIPPY_PORT"
echo "  secrets:    http://localhost:$SECRETS_PORT"
echo "  jarvis:     http://localhost:$PROXY_PORT"
echo "  keeboarder: http://localhost:$KEEBOARDER_PORT   WebSocket: ws://localhost:$KEEBOARDER_PORT/ws/chat"
echo "Attach:  tmux attach -t $SESSION"
echo "Stop:    $0 down"
