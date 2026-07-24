#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<EOF
Usage: $0

Sanity-checks gmail-general's IMAP credentials without starting the full
service: connects, logs in, and fetches the headers of the single most
recent message in the configured folder. Uses the same environment
variables as the app itself (see .env.example in this app's root).

Required:
  IMAP_USERNAME   mailbox address
  IMAP_PASSWORD   app password (prompted, hidden, if unset)

Optional (defaults match GmailEnvs):
  IMAP_HOST    default: imap.gmail.com
  IMAP_PORT    default: 993
  IMAP_SSL     default: true
  IMAP_FOLDER  default: INBOX

Example:
  set -a; source .env; set +a
  ./scripts/check-imap-creds.sh
EOF
  exit 1
}

[[ "${1:-}" == "-h" || "${1:-}" == "--help" ]] && usage

host="${IMAP_HOST:-imap.gmail.com}"
port="${IMAP_PORT:-993}"
ssl="${IMAP_SSL:-true}"
folder="${IMAP_FOLDER:-INBOX}"

if [[ -z "${IMAP_USERNAME:-}" ]]; then
  echo "IMAP_USERNAME is required." >&2
  usage
fi

if [[ -z "${IMAP_PASSWORD:-}" ]]; then
  read -rsp "IMAP password for ${IMAP_USERNAME}: " IMAP_PASSWORD
  echo
fi

scheme="imap"
[[ "$ssl" == "true" ]] && scheme="imaps"

echo "Connecting to ${scheme}://${host}:${port}/${folder} as ${IMAP_USERNAME} ..."

set +e
output=$(curl -s --show-error \
  --url "${scheme}://${host}:${port}/${folder}" \
  --user "${IMAP_USERNAME}:${IMAP_PASSWORD}" \
  --request "FETCH * BODY[HEADER.FIELDS (FROM SUBJECT DATE)]" \
  2>&1)
status=$?
set -e

case $status in
  0)
    echo "OK: credentials are valid. Latest message in ${folder}:"
    echo "---"
    echo "$output"
    ;;
  67)
    echo "FAILED: login denied - wrong IMAP_USERNAME or IMAP_PASSWORD." >&2
    exit 1
    ;;
  *)
    echo "FAILED: could not connect (curl exit code $status)." >&2
    echo "$output" >&2
    exit 1
    ;;
esac
