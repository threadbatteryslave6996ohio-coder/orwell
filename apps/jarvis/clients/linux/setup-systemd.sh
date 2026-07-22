#!/bin/bash
set -euo pipefail

# Keeboarder Ubuntu GNOME - systemd user recorder service setup.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
SERVICE_FILE="$SYSTEMD_USER_DIR/$SYSTEMD_SERVICE_NAME.service"

create_units() {
    mkdir -p "$SYSTEMD_USER_DIR" "$LOGS_DIR"

    cat > "$SERVICE_FILE" << EOF
[Unit]
Description=Keeboarder recorder
After=graphical-session.target
PartOf=graphical-session.target

[Service]
Type=simple
WorkingDirectory=$SCRIPTS_DIR
Environment=PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
Environment=DISPLAY=${DISPLAY:-:0.0}
Environment=XAUTHORITY=${XAUTHORITY:-$HOME/.Xauthority}
Environment=XDG_SESSION_TYPE=${XDG_SESSION_TYPE:-}
Environment=XDG_CURRENT_DESKTOP=${XDG_CURRENT_DESKTOP:-}
Environment=WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-}
ExecStart=/bin/bash $SCRIPTS_DIR/start_recorder.sh
Restart=always
RestartSec=10
StandardOutput=append:$LOGS_DIR/systemd-recorder.log
StandardError=append:$LOGS_DIR/systemd-recorder-error.log

[Install]
WantedBy=default.target
EOF

    systemctl --user daemon-reload
}

load_units() {
    systemctl --user import-environment DISPLAY XAUTHORITY XDG_SESSION_TYPE XDG_CURRENT_DESKTOP WAYLAND_DISPLAY XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS PULSE_SERVER || true
    systemctl --user enable --now "$SYSTEMD_SERVICE_NAME.service"
}

unload_units() {
    systemctl --user disable --now "$SYSTEMD_SERVICE_NAME.service" 2>/dev/null || true
    pkill -f start_recorder.sh 2>/dev/null || true
    pkill -f gnome_wayland_recorder.js 2>/dev/null || true
}

status_units() {
    systemctl --user status "$SYSTEMD_SERVICE_NAME.service" --no-pager || true
}

case "${1:-}" in
    create)
        create_units
        ;;
    load|start)
        create_units
        load_units
        ;;
    unload|stop)
        unload_units
        ;;
    restart)
        unload_units
        create_units
        load_units
        ;;
    status)
        status_units
        ;;
    logs)
        journalctl --user -u "$SYSTEMD_SERVICE_NAME.service" -n 100 --no-pager
        ;;
    *)
        cat << EOF
Usage: ./setup-systemd.sh [command]

Commands:
  create    Create user systemd units
  load      Create, enable, and start the recorder service
  unload    Stop and disable the recorder service
  restart   Recreate and restart the recorder service
  status    Show recorder service status
  logs      Show recent recorder journal logs
EOF
        ;;
esac
