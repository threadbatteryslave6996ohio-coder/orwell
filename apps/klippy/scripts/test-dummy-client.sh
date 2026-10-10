#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-/tmp/klippy-m2}"

AUTH_SERVER_URL="${AUTH_SERVER_URL:-http://localhost:8081}"
REMOTE_SERVER_URL="${REMOTE_SERVER_URL:-http://localhost:8080}"
CLIENT_ID="${CLIENT_ID:-dummy}"
CLIENT_SECRET="${CLIENT_SECRET:-change-me-please}"
# Identities are workspace-scoped, and creating one needs a token from the auth server's `admin`
# workspace — see apps/auth/http-based/server/README.md for where the first admin comes from.
AUTH_WORKSPACE="${AUTH_WORKSPACE:-def}"
AUTH_ADMIN_CLIENT_ID="${AUTH_ADMIN_CLIENT_ID:-local-admin}"
AUTH_ADMIN_SECRET="${AUTH_ADMIN_SECRET:-local-admin-secret}"
CLIP_CONTENT="${CLIP_CONTENT:-example clip content}"

mkdir -p "$ROOT_DIR/logs" "$MAVEN_REPO_LOCAL"
cd "$ROOT_DIR"

admin_token() {
  local payload
  payload="$(python3 -c 'import json, sys; print(json.dumps({"clientId": sys.argv[1], "secret": sys.argv[2]}))' "$AUTH_ADMIN_CLIENT_ID" "$AUTH_ADMIN_SECRET")"
  curl -fsS \
    "$AUTH_SERVER_URL/admin/login" \
    -H 'Content-Type: application/json' \
    --data-binary "$payload" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])'
}

ensure_identity() {
  local payload
  local status
  local token
  if ! token="$(admin_token)"; then
    echo "Failed to log in as admin '$AUTH_ADMIN_CLIENT_ID'. authServer=$AUTH_SERVER_URL" >&2
    echo "Set AUTH_ADMIN_CLIENT_ID/AUTH_ADMIN_SECRET, or start the auth server with AUTH_ADMIN_BOOTSTRAP_*." >&2
    exit 1
  fi
  payload="$(python3 -c 'import json, sys; print(json.dumps({"clientId": sys.argv[1], "secret": sys.argv[2]}))' "$CLIENT_ID" "$CLIENT_SECRET")"
  status="$(
    curl -s -o /dev/null -w '%{http_code}' \
      "$AUTH_SERVER_URL/$AUTH_WORKSPACE/identities" \
      -H 'Content-Type: application/json' \
      -H "X-Client-Id: $AUTH_ADMIN_CLIENT_ID" \
      -H "Authorization: Bearer $token" \
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
    "$AUTH_SERVER_URL/$AUTH_WORKSPACE/login" \
    -H 'Content-Type: application/json' \
    --data-binary "$payload" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])'
}

build_dummy_client() {
  if [ -f clients/dummy/target/klippy-dummy-client-0.1.0-SNAPSHOT.jar ]; then
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
  java -jar clients/dummy/target/klippy-dummy-client-0.1.0-SNAPSHOT.jar "$CLIP_CONTENT"
