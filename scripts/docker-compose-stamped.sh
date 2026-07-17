#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <stack-name> [docker compose args...]"
  exit 1
fi

STACK_NAME="$1"
shift

STAMP="${STACK_TIMESTAMP:-$(date +%y-%m-%d-%H-%M)}"

exec docker compose -p "${STACK_NAME}-${STAMP}" "$@"
