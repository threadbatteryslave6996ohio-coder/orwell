#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-/tmp/clippy-m2}"

mkdir -p "$ROOT_DIR/logs"
mkdir -p "$MAVEN_REPO_LOCAL"
cd "$ROOT_DIR"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ROOT_DIR/.env"
  set +a
fi

export AUTH_LOGGING_FILE_NAME="${AUTH_LOGGING_FILE_NAME:-$ROOT_DIR/logs/auth-server.log}"
export AUTH_SERVER_PORT="${AUTH_SERVER_PORT:-8081}"
export AUTH_JPA_HIBERNATE_DDL_AUTO="${AUTH_JPA_HIBERNATE_DDL_AUTO:-update}"
export AUTH_JPA_JDBC_TIME_ZONE="${AUTH_JPA_JDBC_TIME_ZONE:-UTC}"

mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" \
  -pl ../auth/server -am -Dmaven.test.skip=true package "$@"

exec java -jar ../auth/server/target/auth-server-0.1.0-SNAPSHOT-exec.jar
