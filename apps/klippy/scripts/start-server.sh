#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-/tmp/klippy-m2}"

mkdir -p "$ROOT_DIR/logs"
mkdir -p "$MAVEN_REPO_LOCAL"
cd "$ROOT_DIR"

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ROOT_DIR/.env"
  set +a
fi

export SERVER_PORT="${SERVER_PORT:-8080}"
export SERVER_ADDRESS="${SERVER_ADDRESS:-0.0.0.0}"
export AUTH_BASE_URL="${AUTH_BASE_URL:-http://localhost:8081}"
export LOGGING_FILE_NAME="${LOGGING_FILE_NAME:-$ROOT_DIR/logs/klippy-server.log}"
export SPRING_JPA_HIBERNATE_DDL_AUTO="${SPRING_JPA_HIBERNATE_DDL_AUTO:-update}"
export SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE="${SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE:-UTC}"

MAVEN_OPTS="${MAVEN_OPTS:-}" mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" -pl server -am -Dmaven.test.skip=true package "$@"
exec java -jar server/target/klippy-server-0.1.0-SNAPSHOT-exec.jar
