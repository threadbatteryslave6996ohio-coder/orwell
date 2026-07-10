#!/usr/bin/env bash
# Seeds the secrets manager with all env vars discovered across the project.
#
# Prerequisites:
#   1. The secrets manager must be running (default http://localhost:8085).
#   2. An admin user must already exist in the secrets manager.
#
# Usage:
#   export SECRETS_ADMIN_TOKEN="<bearer-token>"
#   export SECRETS_ADMIN_CLIENT="<client-id>"
#   export SECRETS_BASE_URL="http://localhost:8085"    # optional
#   ./scripts/seed-secrets.sh

set -euo pipefail

BASE_URL="${SECRETS_BASE_URL:-http://localhost:8085}"
TOKEN="${SECRETS_ADMIN_TOKEN:-}"
CLIENT="${SECRETS_ADMIN_CLIENT:-admin}"

if [[ -z "$TOKEN" ]]; then
  echo "ERROR: SECRETS_ADMIN_TOKEN is not set."
  echo ""
  echo "  export SECRETS_ADMIN_TOKEN=\"<bearer-token>\""
  echo "  export SECRETS_ADMIN_CLIENT=\"<client-id>\""
  exit 1
fi

AUTH_H="Authorization: Bearer $TOKEN"
CLIENT_H="X-Client-Id: $CLIENT"

post() {
  curl -sf -H "$AUTH_H" -H "$CLIENT_H" \
    -H "Content-Type: application/json" \
    -X POST "$BASE_URL/admin$1" -d "$2"
}

put() {
  curl -sf -H "$AUTH_H" -H "$CLIENT_H" \
    -H "Content-Type: application/json" \
    -X PUT "$BASE_URL/admin$1" -d "$2"
}

get_json() {
  curl -sf -H "$AUTH_H" -H "$CLIENT_H" \
    "$BASE_URL/admin$1"
}

echo "=== Seeding secrets manager at $BASE_URL ==="
echo ""

# ---------------------------------------------------------------------------
# Helpers: create group / env / bundle and capture IDs
# ---------------------------------------------------------------------------
create_group() {
  local name="$1" desc="$2"
  local resp
  resp=$(post "/groups" "{\"name\":\"$name\",\"description\":\"$desc\"}")
  local id
  id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
  echo "$id"
}

create_env() {
  local group_id="$1" name="$2" value="$3"
  local resp
  resp=$(post "/groups/$group_id/envs" "{\"name\":\"$name\",\"value\":\"$value\"}")
  local id
  id=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
  echo "$id"
}

set_bundle_envs() {
  local bundle_id="$1" env_ids="$2"
  put "/bundles/$bundle_id/envs" "{\"envIds\":$env_ids}" > /dev/null
}

# shellcheck disable=SC2120
list_env_ids() {
  local group_id="$1"
  get_json "/groups/$group_id/envs" | \
    python3 -c "import sys,json; print(json.dumps([e['id'] for e in json.load(sys.stdin)]))"
}

# ============================================================================
# 1. AUTH SERVER
# ============================================================================
echo "[auth-server]"

AUTH_GROUP=$(create_group "auth-server" "Authentication service (apps/auth)")
AUTH_DB_URL=$(create_env "$AUTH_GROUP"  "AUTH_DATASOURCE_URL"      "jdbc:postgresql://localhost:5433/auth")
AUTH_DB_USER=$(create_env "$AUTH_GROUP" "AUTH_DATASOURCE_USERNAME" "auth")
AUTH_DB_PASS=$(create_env "$AUTH_GROUP" "AUTH_DATASOURCE_PASSWORD" "auth")
AUTH_PORT=$(create_env "$AUTH_GROUP"    "AUTH_SERVER_PORT"         "8081")
AUTH_LOG=$(create_env "$AUTH_GROUP"     "AUTH_LOGGING_FILE_NAME"   "logs/auth-server.log")
AUTH_DDL=$(create_env "$AUTH_GROUP"     "AUTH_JPA_HIBERNATE_DDL_AUTO" "update")
AUTH_TZ=$(create_env "$AUTH_GROUP"      "AUTH_JPA_JDBC_TIME_ZONE"  "UTC")

# ============================================================================
# 2. KLIPPY / CLIPBOARD SERVER
# ============================================================================
echo "[klippy-server]"

KLIP_GROUP=$(create_group "klippy-server" "Clipboard service (apps/klippy)")
KLIP_DB_URL=$(create_env "$KLIP_GROUP"  "SPRING_DATASOURCE_URL"      "jdbc:postgresql://localhost:5432/clippy")
KLIP_DB_USER=$(create_env "$KLIP_GROUP" "SPRING_DATASOURCE_USERNAME" "clippy")
KLIP_DB_PASS=$(create_env "$KLIP_GROUP" "SPRING_DATASOURCE_PASSWORD" "clippy")
KLIP_PORT=$(create_env "$KLIP_GROUP"    "SERVER_PORT"                "8082")
KLIP_AUTH=$(create_env "$KLIP_GROUP"    "CLIPPY_AUTH_BASE_URL"       "http://localhost:8081")
KLIP_LOG=$(create_env "$KLIP_GROUP"     "LOGGING_FILE_NAME"          "logs/clippy-server.log")
KLIP_DDL=$(create_env "$KLIP_GROUP"     "SPRING_JPA_HIBERNATE_DDL_AUTO" "update")
KLIP_TZ=$(create_env "$KLIP_GROUP"      "SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE" "UTC")

# ============================================================================
# 3. SECRETS MANAGER SERVER
# ============================================================================
echo "[secrets-server]"

SEC_GROUP=$(create_group "secrets-server" "Secrets manager (apps/secrets-manager)")
SEC_DB_URL=$(create_env "$SEC_GROUP"  "SECRETS_DATASOURCE_URL"      "jdbc:postgresql://localhost:5435/secrets")
SEC_DB_USER=$(create_env "$SEC_GROUP" "SECRETS_DATASOURCE_USERNAME" "secrets")
SEC_DB_PASS=$(create_env "$SEC_GROUP" "SECRETS_DATASOURCE_PASSWORD" "secrets")
SEC_PORT=$(create_env "$SEC_GROUP"    "SECRETS_SERVER_PORT"         "8085")
SEC_LOG=$(create_env "$SEC_GROUP"     "SECRETS_LOGGING_FILE_NAME"   "logs/secrets-server.log")
SEC_DDL=$(create_env "$SEC_GROUP"     "SECRETS_JPA_HIBERNATE_DDL_AUTO" "update")
SEC_TZ=$(create_env "$SEC_GROUP"      "SECRETS_JPA_JDBC_TIME_ZONE"  "UTC")
SEC_AUTH=$(create_env "$SEC_GROUP"    "SECRETS_AUTH_BASE_URL"       "http://localhost:8081")

# ============================================================================
# 4. COMBINED SERVER
# ============================================================================
echo "[combined-server]"

COM_GROUP=$(create_group "combined-server" "Combined all-in-one server (apps/combined-server)")
COM_PORT=$(create_env "$COM_GROUP"          "COMBINED_SERVER_PORT"       "8080")
COM_AUTH_URL=$(create_env "$COM_GROUP"      "AUTH_DATASOURCE_URL"        "jdbc:postgresql://localhost:5433/auth")
COM_AUTH_USER=$(create_env "$COM_GROUP"     "AUTH_DATASOURCE_USERNAME"   "auth")
COM_AUTH_PASS=$(create_env "$COM_GROUP"     "AUTH_DATASOURCE_PASSWORD"   "auth")
COM_CLIP_URL=$(create_env "$COM_GROUP"      "SPRING_DATASOURCE_URL"      "jdbc:postgresql://localhost:5432/clippy")
COM_CLIP_USER=$(create_env "$COM_GROUP"     "SPRING_DATASOURCE_USERNAME" "clippy")
COM_CLIP_PASS=$(create_env "$COM_GROUP"     "SPRING_DATASOURCE_PASSWORD" "clippy")
COM_SEC_URL=$(create_env "$COM_GROUP"       "SECRETS_DATASOURCE_URL"     "jdbc:postgresql://localhost:5435/secrets")
COM_SEC_USER=$(create_env "$COM_GROUP"      "SECRETS_DATASOURCE_USERNAME" "secrets")
COM_SEC_PASS=$(create_env "$COM_GROUP"      "SECRETS_DATASOURCE_PASSWORD" "secrets")
COM_AUTH_BASE=$(create_env "$COM_GROUP"     "CLIPPY_AUTH_BASE_URL"       "http://localhost:8080/auth")
COM_AUTH_PREFIX=$(create_env "$COM_GROUP"   "CLIPPY_AUTH_ROUTE_PREFIX"   "/auth")
COM_CLIP_PREFIX=$(create_env "$COM_GROUP"   "CLIPPY_SERVER_ROUTE_PREFIX" "/klippy")
COM_JARVIS_PREFIX=$(create_env "$COM_GROUP" "JARVIS_SERVER_ROUTE_PREFIX" "/jarvis")
COM_KEEB_PREFIX=$(create_env "$COM_GROUP"   "KEEBOARDER_SERVER_ROUTE_PREFIX" "/keeboarder")
COM_SEC_PREFIX=$(create_env "$COM_GROUP"    "SECRETS_ROUTE_PREFIX"       "/secrets")
COM_LOG=$(create_env "$COM_GROUP"           "LOGGING_FILE_NAME"          "logs/combined-server.log")
COM_AUTH_DDL=$(create_env "$COM_GROUP"      "AUTH_JPA_HIBERNATE_DDL_AUTO" "update")
COM_CLIP_DDL=$(create_env "$COM_GROUP"      "CLIPBOARD_JPA_HIBERNATE_DDL_AUTO" "update")
COM_SEC_DDL=$(create_env "$COM_GROUP"       "SECRETS_JPA_HIBERNATE_DDL_AUTO" "update")
COM_TZ=$(create_env "$COM_GROUP"            "JPA_JDBC_TIME_ZONE"         "UTC")

# ============================================================================
# 5. KEEBOARDER CHAT SERVER
# ============================================================================
echo "[keeboarder-server]"

KEEB_GROUP=$(create_group "keeboarder-server" "WebSocket chat server (apps/keeboarder)")
KEEB_WS_HOST=$(create_env "$KEEB_GROUP" "WEBSOCKET_HOST"  "0.0.0.0")
KEEB_WS_PORT=$(create_env "$KEEB_GROUP" "WEBSOCKET_PORT"  "8025")
KEEB_REDIS_HOST=$(create_env "$KEEB_GROUP" "REDIS_HOST"   "localhost")
KEEB_REDIS_PORT=$(create_env "$KEEB_GROUP" "REDIS_PORT"   "6379")
KEEB_AUTH=$(create_env "$KEEB_GROUP"     "CLIPPY_AUTH_BASE_URL" "http://localhost:8081")

# ============================================================================
# 6. JARVIS BUCKET PROXY
# ============================================================================
echo "[jarvis-proxy]"

JARVIS_GROUP=$(create_group "jarvis-proxy" "S3/Azure object storage proxy (apps/jarvis/bucket/proxy)")

JARVIS_STORAGE=$(create_env "$JARVIS_GROUP"   "PROXY_STORAGE_PROVIDER"        "aws")
JARVIS_MAXFILE=$(create_env "$JARVIS_GROUP"   "PROXY_STORAGE_MAX_FILE_SIZE"   "5368709120")
JARVIS_S3_BUCKET=$(create_env "$JARVIS_GROUP" "PROXY_S3_BUCKET_NAME"          "your-bucket-name")
JARVIS_S3_REGION=$(create_env "$JARVIS_GROUP" "PROXY_S3_REGION"               "us-east-1")
JARVIS_S3_ENDPOINT=$(create_env "$JARVIS_GROUP" "PROXY_S3_ENDPOINT"           "")
JARVIS_S3_PATH=$(create_env "$JARVIS_GROUP"   "PROXY_S3_PATH_STYLE_ACCESS"    "false")
JARVIS_S3_SSE=$(create_env "$JARVIS_GROUP"    "PROXY_S3_SSE"                  "AES256")
JARVIS_AZURE_ACCT=$(create_env "$JARVIS_GROUP" "AZURE_STORAGE_ACCOUNT"        "")
JARVIS_AZURE_CONT=$(create_env "$JARVIS_GROUP" "AZURE_STORAGE_CONTAINER"      "")
JARVIS_AZURE_ENDP=$(create_env "$JARVIS_GROUP" "AZURE_STORAGE_ENDPOINT"       "")
JARVIS_AZURE_CONN=$(create_env "$JARVIS_GROUP" "AZURE_STORAGE_CONNECTION_STRING" "")
JARVIS_AUTH_KEY=$(create_env "$JARVIS_GROUP"  "AUTH_IDENTITY_PROVISIONING_KEY" "")
JARVIS_MGMT_USER=$(create_env "$JARVIS_GROUP" "PROXY_MANAGEMENT_USERNAME"     "")
JARVIS_MGMT_PASS=$(create_env "$JARVIS_GROUP" "PROXY_MANAGEMENT_PASSWORD"     "")
JARVIS_MGMT_SESS=$(create_env "$JARVIS_GROUP" "PROXY_MANAGEMENT_SESSION_SECRET" "")
JARVIS_SRV_URL=$(create_env "$JARVIS_GROUP"   "PROXY_SERVER_URL"              "http://localhost:5000")
JARVIS_AUDIT=$(create_env "$JARVIS_GROUP"     "PROXY_AUDIT_FILE"              "logs/audit.log")

# ============================================================================
# 7. JARVIS DETECTION
# ============================================================================
echo "[jarvis-detection]"

DETECT_GROUP=$(create_group "jarvis-detection" "Person detection service (apps/jarvis/detection)")
DETECT_HOST=$(create_env "$DETECT_GROUP"  "DETECTION_SERVER_HOST"       "127.0.0.1")
DETECT_PORT=$(create_env "$DETECT_GROUP"  "DETECTION_SERVER_PORT"       "9001")
DETECT_ALERT_URL=$(create_env "$DETECT_GROUP" "DETECTION_ALERT_URL"     "http://127.0.0.1:9000/alerts")
DETECT_COOLDOWN=$(create_env "$DETECT_GROUP" "DETECTION_ALERT_COOLDOWN_SECONDS" "60")
DETECT_CONFIDENCE=$(create_env "$DETECT_GROUP" "DETECTION_MIN_CONFIDENCE" "0.0")

# ============================================================================
# 8. JARVIS ALERTING
# ============================================================================
echo "[jarvis-alerting]"

ALERT_GROUP=$(create_group "jarvis-alerting" "Alert dispatch service (apps/jarvis/bucket/alerting)")
ALERT_HOST=$(create_env "$ALERT_GROUP"  "ALERT_SERVER_HOST"       "127.0.0.1")
ALERT_PORT=$(create_env "$ALERT_GROUP"  "ALERT_SERVER_PORT"       "9000")
ALERT_EMAIL_ENABLED=$(create_env "$ALERT_GROUP" "ALERT_EMAIL_ENABLED" "false")
ALERT_EMAIL_TO=$(create_env "$ALERT_GROUP"    "ALERT_EMAIL_TO"    "")
ALERT_SMTP_HOST=$(create_env "$ALERT_GROUP"   "SMTP_HOST"         "")
ALERT_SMTP_PORT=$(create_env "$ALERT_GROUP"   "SMTP_PORT"         "587")
ALERT_SMTP_USER=$(create_env "$ALERT_GROUP"   "SMTP_USERNAME"     "")
ALERT_SMTP_PASS=$(create_env "$ALERT_GROUP"   "SMTP_PASSWORD"     "")
ALERT_SMTP_TLS=$(create_env "$ALERT_GROUP"    "SMTP_USE_TLS"      "true")
ALERT_LOG_FILE=$(create_env "$ALERT_GROUP"    "ALERT_LOG_FILE"    "/var/log/streaming/alerts.log")

# ============================================================================
# 9. JARVIS STREAMING
# ============================================================================
echo "[jarvis-streaming]"

STREAM_GROUP=$(create_group "jarvis-streaming" "Frame analysis worker (apps/jarvis/bucket/proxy)")
STREAM_ENDPOINT=$(create_env "$STREAM_GROUP" "STREAM_ANALYSIS_ENDPOINT" "")

# ============================================================================
# 10. KLIPPY CLIENT
# ============================================================================
echo "[klippy-client]"

CLIENT_GROUP=$(create_group "klippy-client" "Desktop/Android clipboard client (apps/klippy/clients)")
CLIENT_REMOTE=$(create_env "$CLIENT_GROUP" "REMOTE_SERVER_URL"               "")
CLIENT_ID_VAR=$(create_env "$CLIENT_GROUP" "CLIENT_ID"                       "")
CLIENT_TOKEN_VAR=$(create_env "$CLIENT_GROUP" "CLIENT_TOKEN"                 "")
CLIENT_SECRET=$(create_env "$CLIENT_GROUP"  "CLIENT_SECRET"                  "")
CLIENT_AUTH_URL=$(create_env "$CLIENT_GROUP" "AUTH_SERVER_URL"               "")
CLIENT_POLL=$(create_env "$CLIENT_GROUP"    "CLIPBOARD_POLL_INTERVAL_MS"     "500")
CLIENT_BACKEND=$(create_env "$CLIENT_GROUP" "CLIPBOARD_BACKEND"              "")
CLIENT_OFFLINE_SOCKET=$(create_env "$CLIENT_GROUP" "OFFLINE_FILE_LOCKER_SOCKET" "")
CLIENT_SYNC_INTERVAL=$(create_env "$CLIENT_GROUP" "OFFLINE_SYNC_INTERVAL_MINUTES" "5")

# ============================================================================
# BUNDLES
# ============================================================================
echo ""
echo "=== Creating bundles ==="

# --- Database bundle ---
DB_BUNDLE=$(post "/bundles" '{"name":"database-connections","description":"All database JDBC URLs and credentials"}')
DB_BUNDLE_ID=$(echo "$DB_BUNDLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$DB_BUNDLE_ID" "$( \
  echo '[' \
    "$AUTH_DB_URL,$AUTH_DB_USER,$AUTH_DB_PASS," \
    "$KLIP_DB_URL,$KLIP_DB_USER,$KLIP_DB_PASS," \
    "$SEC_DB_URL,$SEC_DB_USER,$SEC_DB_PASS," \
    "$COM_AUTH_URL,$COM_AUTH_USER,$COM_AUTH_PASS," \
    "$COM_CLIP_URL,$COM_CLIP_USER,$COM_CLIP_PASS," \
    "$COM_SEC_URL,$COM_SEC_USER,$COM_SEC_PASS" \
  ']' | sed 's/ //g' \
)" && echo "  bundle 'database-connections' created"

# --- Auth bundle ---
AUTH_BUNDLE=$(post "/bundles" '{"name":"auth-config","description":"Authentication-related configuration"}')
AUTH_BUNDLE_ID=$(echo "$AUTH_BUNDLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$AUTH_BUNDLE_ID" "$( \
  echo '[' "$KLIP_AUTH,$KEEB_AUTH,$SEC_AUTH,$COM_AUTH_BASE,$COM_AUTH_PREFIX,$JARVIS_AUTH_KEY" ']' | sed 's/ //g' \
)" && echo "  bundle 'auth-config' created"

# --- Storage bundle ---
STORAGE_BUNDLE=$(post "/bundles" '{"name":"object-storage","description":"S3 / Azure object storage settings"}')
STORAGE_BUNDLE_ID=$(echo "$STORAGE_BUNDLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$STORAGE_BUNDLE_ID" "$( \
  echo '[' "$JARVIS_STORAGE,$JARVIS_MAXFILE,$JARVIS_S3_BUCKET,$JARVIS_S3_REGION,$JARVIS_S3_ENDPOINT,$JARVIS_S3_PATH,$JARVIS_S3_SSE,$JARVIS_AZURE_ACCT,$JARVIS_AZURE_CONT,$JARVIS_AZURE_ENDP,$JARVIS_AZURE_CONN" ']' | sed 's/ //g' \
)" && echo "  bundle 'object-storage' created"

# --- Messaging bundle ---
MESSAGING_BUNDLE=$(post "/bundles" '{"name":"messaging","description":"WebSocket and Redis configuration"}')
MESSAGING_BUNDLE_ID=$(echo "$MESSAGING_BUNDLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$MESSAGING_BUNDLE_ID" "$( \
  echo '[' "$KEEB_WS_HOST,$KEEB_WS_PORT,$KEEB_REDIS_HOST,$KEEB_REDIS_PORT" ']' | sed 's/ //g' \
)" && echo "  bundle 'messaging' created"

# --- Routes bundle ---
ROUTES_BUNDLE=$(post "/bundles" '{"name":"route-prefixes","description":"HTTP route prefix configuration for combined mode"}')
ROUTES_BUNDLE_ID=$(echo "$ROUTES_BUNDLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$ROUTES_BUNDLE_ID" "$( \
  echo '[' "$COM_AUTH_PREFIX,$COM_CLIP_PREFIX,$COM_JARVIS_PREFIX,$COM_KEEB_PREFIX,$COM_SEC_PREFIX" ']' | sed 's/ //g' \
)" && echo "  bundle 'route-prefixes' created"

# --- Ports bundle ---
PORTS_BUNDLE=$(post "/bundles" '{"name":"server-ports","description":"All service ports"}')
PORTS_BUNDLE_ID=$(echo "$PORTS_BUNDLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$PORTS_BUNDLE_ID" "$( \
  echo '[' "$AUTH_PORT,$KLIP_PORT,$SEC_PORT,$COM_PORT,$DETECT_PORT,$ALERT_PORT,$KEEB_WS_PORT,$KEEB_REDIS_PORT" ']' | sed 's/ //g' \
)" && echo "  bundle 'server-ports' created"

# --- JPA / Hibernate bundle ---
JPA_BUNDLE=$(post "/bundles" '{"name":"jpa-hibernate","description":"JPA / Hibernate DDL and timezone settings"}')
JPA_BUNDLE_ID=$(echo "$JPA_BUNDLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$JPA_BUNDLE_ID" "$( \
  echo '[' "$AUTH_DDL,$AUTH_TZ,$KLIP_DDL,$KLIP_TZ,$SEC_DDL,$SEC_TZ,$COM_AUTH_DDL,$COM_CLIP_DDL,$COM_SEC_DDL,$COM_TZ" ']' | sed 's/ //g' \
)" && echo "  bundle 'jpa-hibernate' created"

# --- Jarvis full bundle ---
JARVIS_FULL=$(post "/bundles" '{"name":"jarvis-full","description":"All Jarvis services configuration"}')
JARVIS_FULL_ID=$(echo "$JARVIS_FULL" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
set_bundle_envs "$JARVIS_FULL_ID" "$( \
  json_array=$(get_json "/groups/$JARVIS_GROUP/envs"; get_json "/groups/$DETECT_GROUP/envs"; get_json "/groups/$ALERT_GROUP/envs"; get_json "/groups/$STREAM_GROUP/envs")
  echo "$json_array" | python3 -c "import sys,json; all_ids=[]; [all_ids.extend(e['id'] for e in json.load(l)) for l in sys.stdin]; print(json.dumps(all_ids))"
)" && echo "  bundle 'jarvis-full' created"

echo ""
echo "=== Seeding complete ==="
echo ""
echo "Groups created:"
echo "  auth-server, klippy-server, secrets-server, combined-server"
echo "  keeboarder-server, jarvis-proxy, jarvis-detection"
echo "  jarvis-alerting, jarvis-streaming, klippy-client"
echo ""
echo "Bundles created:"
echo "  database-connections, auth-config, object-storage, messaging"
echo "  route-prefixes, server-ports, jpa-hibernate, jarvis-full"
