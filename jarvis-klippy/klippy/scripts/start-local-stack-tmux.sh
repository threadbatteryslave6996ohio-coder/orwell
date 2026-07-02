#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ATTACH=1
SESSION_NAME="clippy"
LOCKER_SOCKET="/tmp/clippy-offline-file-locker.sock"

for arg in "$@"; do
  case "$arg" in
    --detached)
      ATTACH=0
      ;;
    *)
      SESSION_NAME="$arg"
      ;;
  esac
done

if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ROOT_DIR/.env"
  set +a
fi

LOCKER_SOCKET="${OFFLINE_FILE_LOCKER_SOCKET:-$LOCKER_SOCKET}"

if ! command -v tmux >/dev/null 2>&1; then
  echo "tmux is required but was not found on PATH." >&2
  exit 1
fi

if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
  echo "tmux session '$SESSION_NAME' already exists." >&2
  echo "Attach with: tmux attach -t $SESSION_NAME" >&2
  exit 1
fi

run_in_pane() {
  local target="$1"
  shift
  tmux send-keys -t "$target" "cd '$ROOT_DIR' && $*" C-m
}

tmux new-session -d -s "$SESSION_NAME" -n stack
tmux split-window -h -t "$SESSION_NAME:stack"
tmux split-window -v -t "$SESSION_NAME:stack.0"
tmux split-window -v -t "$SESSION_NAME:stack.1"
tmux select-layout -t "$SESSION_NAME:stack" tiled

tmux select-pane -t "$SESSION_NAME:stack.0" -T auth
tmux select-pane -t "$SESSION_NAME:stack.1" -T server
tmux select-pane -t "$SESSION_NAME:stack.2" -T locker
tmux select-pane -t "$SESSION_NAME:stack.3" -T linux-client

tmux new-window -t "$SESSION_NAME" -n sync
tmux select-pane -t "$SESSION_NAME:sync.0" -T offline-sync

run_in_pane "$SESSION_NAME:stack.0" "./scripts/start-auth-server.sh"
run_in_pane "$SESSION_NAME:stack.1" "./scripts/start-server.sh"
run_in_pane "$SESSION_NAME:stack.2" "./scripts/start-file-locker.sh"
run_in_pane "$SESSION_NAME:stack.3" "until [ -S '$LOCKER_SOCKET' ]; do echo 'Waiting for file-locker socket: $LOCKER_SOCKET'; sleep 1; done; ./scripts/start-linux-client.sh"
run_in_pane "$SESSION_NAME:sync.0" "until [ -S '$LOCKER_SOCKET' ]; do echo 'Waiting for file-locker socket: $LOCKER_SOCKET'; sleep 1; done; ./scripts/sync-offline-client.sh"

tmux select-window -t "$SESSION_NAME:stack"

if [ "$ATTACH" -eq 1 ]; then
  exec tmux attach -t "$SESSION_NAME"
fi

echo "Started tmux session '$SESSION_NAME'."
echo "Attach with: tmux attach -t $SESSION_NAME"
