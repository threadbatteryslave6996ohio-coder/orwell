#!/usr/bin/env bash
# Rebuilds every image and brings the whole stack up.
#
# A thin alias for the compose wrapper, kept because it is what people have in their shell
# history. `scripts/stack.sh` is the general form and takes any compose subcommand.
set -euo pipefail

exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/stack.sh" \
    up -d --build --remove-orphans
