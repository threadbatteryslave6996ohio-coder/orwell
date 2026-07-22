#!/bin/bash
set -euo pipefail

# Keeboarder Ubuntu GNOME - Management Script

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

print_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}x $1${NC}"
}

start_all() {
    print_header "Starting Keeboarder Services"
    "$SCRIPT_DIR/setup-systemd.sh" load
}

stop_all() {
    print_header "Stopping Keeboarder Services"
    "$SCRIPT_DIR/setup-systemd.sh" unload
}

restart_all() {
    print_header "Restarting Keeboarder Services"
    "$SCRIPT_DIR/setup-systemd.sh" restart
}

show_status() {
    print_header "Keeboarder System Status"

    if systemctl --user is-active --quiet "$SYSTEMD_SERVICE_NAME.service"; then
        print_success "Recorder service is active"
    else
        print_error "Recorder service is not active"
    fi

    echo ""
    echo -e "${YELLOW}Session${NC}"
    echo "  Desktop: ${XDG_CURRENT_DESKTOP:-unknown}"
    echo "  Session type: ${XDG_SESSION_TYPE:-unknown}"
    echo "  Display: ${DISPLAY:-unknown}"

    echo ""
    echo -e "${YELLOW}Processes${NC}"
    pgrep -af "ffmpeg|start_recorder.sh|gnome_wayland_recorder.js" || true

    echo ""
    echo -e "${YELLOW}Disk Usage${NC}"
    if [ -d "$RECORDINGS_DIR" ]; then
        du -sh "$RECORDINGS_DIR" "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR" 2>/dev/null || true
    fi
}

show_logs() {
    local log_type="${1:-all}"

    case "$log_type" in
        recorder)
            tail -50 "$LOGS_DIR/recorder.log" 2>/dev/null || true
            ;;
        journal)
            journalctl --user -u "$SYSTEMD_SERVICE_NAME.service" -n 100 --no-pager
            ;;
        *)
            print_header "Recent Logs"
            echo -e "${YELLOW}Recorder${NC}"
            tail -20 "$LOGS_DIR/recorder.log" 2>/dev/null || true
            ;;
    esac
}

cleanup_old_files() {
    local days="${1:-7}"
    print_header "Cleaning Local Files Older Than $days Days"

    local file_count=0
    for dir in "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR"; do
        [ -d "$dir" ] || continue
        while IFS= read -r file; do
            rm -f "$file"
            ((++file_count))
        done < <(find "$dir" -maxdepth 1 -type f -mtime +"$days")
    done

    print_success "Removed $file_count old files"
}

print_usage() {
    cat << EOF
${BLUE}Keeboarder Ubuntu GNOME Management Tool${NC}

${YELLOW}Usage:${NC}
  ./manage.sh [command] [options]

${YELLOW}Commands:${NC}
  start              Start the recorder service
  stop               Stop the recorder service
  restart            Restart the recorder service
  status             Show current status
  logs               Show recent logs
  logs recorder      Show recorder log
  logs journal       Show systemd journal
  cleanup [days]     Remove local files older than N days
  help               Show this help message

Uploads are handled by the syncer client at apps/jarvis/clients/syncer/.
EOF
}

case "${1:-}" in
    start)
        start_all
        ;;
    stop)
        stop_all
        ;;
    restart)
        restart_all
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs "${2:-all}"
        ;;
    cleanup)
        cleanup_old_files "${2:-7}"
        ;;
    help|--help|-h)
        print_usage
        ;;
    *)
        print_usage
        echo ""
        show_status
        ;;
esac
