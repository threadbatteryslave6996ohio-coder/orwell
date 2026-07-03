#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-/tmp/clippy-m2}"

AUTH_SERVER_URL="${AUTH_SERVER_URL:-http://localhost:8081}"
REMOTE_SERVER_URL="${REMOTE_SERVER_URL:-http://localhost:8080}"
CLIENT_ID="${CLIENT_ID:-dummy}"
CLIENT_SECRET="${CLIENT_SECRET:-change-me-please}"
CLIP_CONTENT="${CLIP_CONTENT:-example clip content}"

mkdir -p "$ROOT_DIR/logs" "$MAVEN_REPO_LOCAL"
cd "$ROOT_DIR"

ensure_identity() {
  local payload
  local status
  payload="$(python3 -c 'import json, sys; print(json.dumps({"clientId": sys.argv[1], "secret": sys.argv[2]}))' "$CLIENT_ID" "$CLIENT_SECRET")"
  status="$(
    curl -s -o /dev/null -w '%{http_code}' \
      "$AUTH_SERVER_URL/identities" \
      -H 'Content-Type: application/json' \
      --data-binary "$payload"
  )"

  case "$status" in
    201|409)
      ;;
    *)
      echo "Failed to create or verify identity. authServer=$AUTH_SERVER_URL httpStatus=$status" >&2
      exit 1
      ;;
  esac
}

login_token() {
  local payload
  payload="$(python3 -c 'import json, sys; print(json.dumps({"clientId": sys.argv[1], "secret": sys.argv[2]}))' "$CLIENT_ID" "$CLIENT_SECRET")"
  curl -fsS \
    "$AUTH_SERVER_URL/login" \
    -H 'Content-Type: application/json' \
    --data-binary "$payload" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])'
}

build_dummy_client() {
  if [ -f clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar ]; then
    return
  fi

  mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" -pl clients/dummy -am -Dmaven.test.skip=true package
}

ensure_identity
CLIENT_TOKEN="$(login_token)"
build_dummy_client "$@"

exec env \
  AUTH_SERVER_URL="$AUTH_SERVER_URL" \
  REMOTE_SERVER_URL="$REMOTE_SERVER_URL" \
  CLIENT_ID="$CLIENT_ID" \
  CLIENT_TOKEN="$CLIENT_TOKEN" \
  java -jar clients/dummy/target/clippy-dummy-client-0.1.0-SNAPSHOT.jar "$CLIP_CONTENT"
