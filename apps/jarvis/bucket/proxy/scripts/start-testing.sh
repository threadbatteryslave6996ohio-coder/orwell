#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

docker compose \
  -p testing \
  -f "$PROJECT_DIR/docker/deployment/compose.yaml" \
  --env-file "$PROJECT_DIR/.env.testing" \
  up --build -d
