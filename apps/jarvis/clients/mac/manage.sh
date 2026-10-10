#!/bin/bash
set -euo pipefail

# Jarvis macOS recorder — management commands.
# Colours, print helpers and the retention sweep come from the shared library; what stays here is
# the launchd half, which has no Ubuntu equivalent.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/console.sh
source "$SCRIPT_DIR/../lib/console.sh"
# shellcheck source=config.sh
source "$SCRIPT_DIR/config.sh"

LAUNCHAGENT_PLIST="$HOME/Library/LaunchAgents/$LAUNCHAGENT_LABEL.plist"

start_all() {
    print_header "Starting Jarvis Recorder"

    print_section "Starting recorder..."
    launchctl load "$LAUNCHAGENT_PLIST" 2>/dev/null || true
    sleep 2

    if pgrep -f start_recorder.sh >/dev/null 2>&1; then
        print_success "Recorder is running"
    else
        print_error "Recorder failed to start"
    fi
}

stop_all() {
    print_header "Stopping Jarvis Recorder"

    print_section "Stopping recorder..."
    launchctl unload "$LAUNCHAGENT_PLIST" 2>/dev/null || true
    # The recorder's own SIGTERM handler stops its captures; these are the belt-and-braces sweep
    # for a recorder that was killed rather than asked to stop and left ffmpeg behind.
    pkill -f start_recorder.sh || true
    pkill -f ffmpeg || true
    sleep 1

    if pgrep -f start_recorder.sh >/dev/null 2>&1; then
        print_error "Recorder still running"
    else
        print_success "Recorder stopped"
    fi
}

restart_all() {
    print_header "Restarting Jarvis Recorder"

    stop_all
    sleep 2
    start_all
}

show_status() {
    print_header "Jarvis Recorder Status"

    print_section "Recorder Status"
    if pgrep -x ffmpeg >/dev/null 2>&1; then
        print_success "FFmpeg is running"
        pgrep -x ffmpeg | while read -r pid; do print_info "PID $pid"; done
    else
        print_error "FFmpeg is not running"
    fi

    if launchctl list | grep -q "$LAUNCHAGENT_LABEL"; then
        print_success "LaunchAgent is loaded"
    else
        print_error "LaunchAgent is not loaded"
    fi

    echo ""
    print_disk_usage
}

show_logs() {
    print_header "Recent Logs"
    case "${1:-all}" in
        recorder)
            print_recorder_log 50
            ;;
        *)
            print_recorder_log 20
            ;;
    esac
}

# The configured device names, which are the usual reason a fresh install records nothing.
full_status() {
    print_header "Jarvis Recorder Full Report"

    print_section "Configuration"
    print_info "Device: $DEVICE_HOSTNAME"
    print_info "Screen Device: $SCREEN_DEVICE"
    print_info "Microphone Device: $MIC_DEVICE"
    print_info "System Audio Device: $SYSTEM_AUDIO_DEVICE"

    echo ""
    show_status
}

print_usage() {
    cat << EOF
${BLUE}Jarvis macOS Recorder Management Tool${NC}

${YELLOW}Usage:${NC}
  ./manage.sh [command] [options]

${YELLOW}Commands:${NC}
  start          Start the recorder
  stop           Stop the recorder
  restart        Restart the recorder
  status         Show current status
  full-status    Show detailed system report
  logs           Show recent logs
  logs recorder  Show recorder logs
  cleanup [days] Remove local files older than N days (default: 7)
  help           Show this help message

${YELLOW}Examples:${NC}
  ./manage.sh status                 # Check current status
  ./manage.sh logs recorder          # View recorder logs
  ./manage.sh cleanup 30             # Remove files older than 30 days

${YELLOW}Uploads:${NC}
  This is a record-only client. Recordings are uploaded by the separate
  syncer client at apps/jarvis/clients/syncer/ (run it on a timer).

EOF
}

if [ $# -eq 0 ]; then
    print_usage
    show_status
    exit 0
fi

case "$1" in
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
    full-status)
        full_status
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
        print_error "Unknown command: $1"
        echo ""
        print_usage
        exit 1
        ;;
esac

echo ""
