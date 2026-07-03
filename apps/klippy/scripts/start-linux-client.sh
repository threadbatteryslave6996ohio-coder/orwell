#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-/tmp/clippy-m2}"

mkdir -p "$MAVEN_REPO_LOCAL"
cd "$ROOT_DIR"

mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" \
  -pl clients/linux -am -Dmaven.test.skip=true package

# The client loads $ROOT_DIR/.env itself; inherited shell variables take precedence.
exec java -jar clients/linux/target/clippy-linux-client-0.1.0-SNAPSHOT.jar "$@"
