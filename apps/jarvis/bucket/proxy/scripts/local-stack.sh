#!/bin/bash
set -uo pipefail

# Local test stack for the bucket proxy.
#
# Brings up an S3-compatible bucket (MinIO) and the auth server's PostgreSQL in
# Docker, then runs the auth server and the proxy configured to use them. This
# lets you exercise the full upload path (client -> proxy -> bucket) without AWS
# or a remote auth server.
#
# Usage:
#   ./local-stack.sh up        # start MinIO + Postgres, create the bucket
#   ./local-stack.sh auth      # build (if needed) and run the auth server
#   ./local-stack.sh proxy     # build (if needed) and run the proxy against MinIO
#   ./local-stack.sh identity <clientId> <secret>   # create an upload identity
#   ./local-stack.sh status    # show container + endpoint status
#   ./local-stack.sh down      # stop and remove the containers
#
# `auth` and `proxy` run in the foreground; use separate terminals (or append &).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# NOTE: REPO_ROOT is misnamed - it is the apps/ directory, which is what the existing
# "$REPO_ROOT/auth/..." paths below rely on. MONO_ROOT is the actual repository root.
REPO_ROOT="$(cd "$PROXY_DIR/../../.." && pwd)"
MONO_ROOT="$(cd "$REPO_ROOT/.." && pwd)"

# ---- Configuration (override via environment) ----
MINIO_CONTAINER="${MINIO_CONTAINER:-klippy-minio}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}"
BUCKET="${BUCKET:-keeboarder-recordings}"
S3_ENDPOINT="${S3_ENDPOINT:-http://localhost:9000}"
AUTH_PORT="${AUTH_PORT:-8081}"
PROXY_PORT="${PROXY_PORT:-5000}"
PG_PORT="${PG_PORT:-5432}"

log() { printf '[local-stack] %s\n' "$*"; }
die() { printf '[local-stack] ERROR: %s\n' "$*" >&2; exit 1; }

require_docker() { command -v docker >/dev/null 2>&1 || die "docker is required"; }

cmd_up() {
    require_docker

    if [ -z "$(docker ps -q -f name="^${MINIO_CONTAINER}$")" ]; then
        docker rm -f "$MINIO_CONTAINER" >/dev/null 2>&1 || true
        log "Starting MinIO ($MINIO_CONTAINER) on :9000 / console :9001"
        docker run -d --name "$MINIO_CONTAINER" -p 9000:9000 -p 9001:9001 \
            -e MINIO_ROOT_USER="$MINIO_ROOT_USER" \
            -e MINIO_ROOT_PASSWORD="$MINIO_ROOT_PASSWORD" \
            -v klippy-minio-data:/data \
            minio/minio server /data --console-address ":9001" >/dev/null \
            || die "failed to start MinIO"
    else
        log "MinIO already running"
    fi

    # Postgres comes from the one shared stack, not a private container.
    log "Starting shared PostgreSQL (docker-compose.all-services.yml)"
    docker compose -f "$MONO_ROOT/docker-compose.all-services.yml" up -d db \
        || die "failed to start the shared PostgreSQL"

    log "Creating bucket '$BUCKET' (idempotent)"
    docker run --rm --network=host --entrypoint sh minio/mc -c \
        "for i in \$(seq 1 15); do mc alias set local $S3_ENDPOINT $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD >/dev/null 2>&1 && break; sleep 1; done; mc mb -p local/$BUCKET 2>&1 | grep -v 'already own' || true; mc ls local" \
        || die "failed to create bucket"

    log "Stack up. Next: '$0 auth' then '$0 proxy' (separate terminals)."
}

cmd_auth() {
    local jar="$REPO_ROOT/auth/http-based/server/target/auth-http-server-0.1.0-SNAPSHOT-exec.jar"
    if [ ! -f "$jar" ]; then
        log "Building auth server jar..."
        (cd "$REPO_ROOT" && mvn -q -Pservers -pl auth/http-based/server -am package -DskipTests) || die "auth build failed"
    fi
    log "Running auth server on :$AUTH_PORT (Ctrl-C to stop)"
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT="$AUTH_PORT" \
    AUTH_DATASOURCE_URL="jdbc:postgresql://localhost:${PG_PORT}/auth" \
    AUTH_DATASOURCE_USERNAME=auth \
    AUTH_DATASOURCE_PASSWORD=auth \
    LOGGING_FILE_NAME="$REPO_ROOT/auth/http-based/server/target/auth-spring.log" \
    AUTH_JPA_HIBERNATE_DDL_AUTO=update \
    AUTH_JPA_JDBC_TIME_ZONE=UTC \
    exec java -Dcustom.logger.dir="$REPO_ROOT/auth/http-based/server/target" -jar "$jar"
}

cmd_proxy() {
    local jar
    jar="$(ls "$PROXY_DIR"/target/jarvis-bucket-proxy-*.jar 2>/dev/null | grep -v original | head -n1)"
    if [ -z "$jar" ]; then
        log "Building proxy jar..."
        (cd "$PROXY_DIR" && mvn -q package -DskipTests) || die "proxy build failed"
        jar="$(ls "$PROXY_DIR"/target/jarvis-bucket-proxy-*.jar 2>/dev/null | grep -v original | head -n1)"
    fi
    log "Running proxy on :$PROXY_PORT against MinIO (SSE disabled for local MinIO)"
    # AWS_* creds are the MinIO root credentials; SSE is disabled because a local
    # MinIO has no KMS configured.
    AWS_ACCESS_KEY_ID="$MINIO_ROOT_USER" \
    AWS_SECRET_ACCESS_KEY="$MINIO_ROOT_PASSWORD" \
    AWS_REGION=us-east-1 \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT="$PROXY_PORT" \
    AUTH_BASE_URL="http://localhost:${AUTH_PORT}" \
    exec java \
        -Dproxy.s3.bucket-name="$BUCKET" \
        -Dproxy.s3.region=us-east-1 \
        -Dproxy.s3.endpoint="$S3_ENDPOINT" \
        -Dproxy.s3.path-style-access=true \
        -Dproxy.s3.server-side-encryption= \
        -jar "$jar"
}

cmd_identity() {
    local client_id="${1:-}" secret="${2:-}"
    [ -n "$client_id" ] && [ -n "$secret" ] || die "usage: $0 identity <clientId> <secret>  (secret >= 8 chars)"
    log "Creating identity '$client_id' via auth server :$AUTH_PORT"
    curl -s -w '\n-> HTTP %{http_code}\n' -H 'Content-Type: application/json' \
        -d "{\"clientId\":\"$client_id\",\"secret\":\"$secret\"}" \
        "http://localhost:${AUTH_PORT}/identities"
}

cmd_status() {
    require_docker
    echo "Containers:"
    docker ps --filter "name=$MINIO_CONTAINER" --filter "name=$PG_CONTAINER" \
        --format '  {{.Names}}  {{.Status}}  {{.Ports}}'
    echo "Endpoints:"
    printf '  MinIO S3   %s -> %s\n' "$S3_ENDPOINT" "$(curl -s -o /dev/null -w '%{http_code}' "$S3_ENDPOINT/minio/health/live" 2>/dev/null || echo down)"
    printf '  Auth       http://localhost:%s -> %s\n' "$AUTH_PORT" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"clientId":"x","secret":"x"}' "http://localhost:${AUTH_PORT}/login" 2>/dev/null || echo down)"
    printf '  Proxy      http://localhost:%s/health -> %s\n' "$PROXY_PORT" "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${PROXY_PORT}/health" 2>/dev/null || echo down)"
}

cmd_down() {
    require_docker
    log "Removing containers (named volumes are kept)"
    docker rm -f "$MINIO_CONTAINER" "$PG_CONTAINER" 2>/dev/null || true
    log "Note: stop the auth/proxy JVMs manually if running."
}

case "${1:-}" in
    up) cmd_up ;;
    auth) cmd_auth ;;
    proxy) cmd_proxy ;;
    identity) shift; cmd_identity "$@" ;;
    status) cmd_status ;;
    down) cmd_down ;;
    *) echo "usage: $0 {up|auth|proxy|identity <clientId> <secret>|status|down}"; exit 1 ;;
esac
