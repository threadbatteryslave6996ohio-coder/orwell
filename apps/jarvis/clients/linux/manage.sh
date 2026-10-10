#!/bin/bash
set -euo pipefail

# Jarvis Ubuntu recorder — management commands.
# Colours, print helpers and the retention sweep come from the shared library; what stays here is
# the systemd half, which has no macOS equivalent.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/console.sh
source "$SCRIPT_DIR/../lib/console.sh"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"

start_all() {
    print_header "Starting Jarvis Recorder"
    "$SCRIPT_DIR/setup-systemd.sh" load
}

stop_all() {
    print_header "Stopping Jarvis Recorder"
    "$SCRIPT_DIR/setup-systemd.sh" unload
}

restart_all() {
    print_header "Restarting Jarvis Recorder"
    "$SCRIPT_DIR/setup-systemd.sh" restart
}

show_status() {
    print_header "Jarvis Recorder Status"

    if systemctl --user is-active --quiet "$SYSTEMD_SERVICE_NAME.service"; then
        print_success "Recorder service is active"
    else
        print_error "Recorder service is not active"
    fi

    echo ""
    # Which session is running decides which capture backend the recorder can use at all, so it is
    # the first thing worth seeing when recordings are unexpectedly empty.
    print_section "Session"
    print_info "Desktop: ${XDG_CURRENT_DESKTOP:-unknown}"
    print_info "Session type: ${XDG_SESSION_TYPE:-unknown}"
    print_info "Display: ${DISPLAY:-unknown}"

    echo ""
    print_section "Processes"
    pgrep -af "ffmpeg|start_recorder.sh|gnome_wayland_recorder.js" || true

    echo ""
    print_disk_usage
}

show_logs() {
    case "${1:-all}" in
        recorder)
            print_recorder_log 50
            ;;
        journal)
            journalctl --user -u "$SYSTEMD_SERVICE_NAME.service" -n 100 --no-pager
            ;;
        *)
            print_header "Recent Logs"
            print_recorder_log 20
            ;;
    esac
}

print_usage() {
    cat << EOF
${BLUE}Jarvis Ubuntu Recorder Management Tool${NC}

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
  cleanup [days]     Remove local files older than N days (default: 7)
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
