#!/bin/bash
set -euo pipefail

# Check completed local videos against the configured server and upload only
# videos that are absent or have a different size. Local copies are retained by
# default through DELETE_AFTER_UPLOAD=false.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/upload_to_s3.sh" --videos-only
