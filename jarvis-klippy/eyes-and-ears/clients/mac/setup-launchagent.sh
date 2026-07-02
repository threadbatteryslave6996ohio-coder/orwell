#!/bin/bash

# Keeboarder macOS - LaunchAgent Setup Script
# Standalone script to setup/reload LaunchAgent for auto-start

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Keeboarder LaunchAgent Manager${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Paths
LAUNCHAGENT_DIR="$HOME/Library/LaunchAgents"
LAUNCHAGENT_PLIST="$LAUNCHAGENT_DIR/com.keeboarder.recorder.plist"

# ============================================================
# Functions
# ============================================================

load_launchagent() {
    echo -e "${YELLOW}Loading LaunchAgent...${NC}"
    
    # First unload if already loaded
    if launchctl list | grep -q "com.keeboarder.recorder"; then
        echo "Unloading existing LaunchAgent..."
        launchctl unload "$LAUNCHAGENT_PLIST" 2>/dev/null || true
        sleep 1
    fi
    
    # Load the plist
    launchctl load "$LAUNCHAGENT_PLIST"
    
    echo -e "${GREEN}✓ LaunchAgent loaded${NC}"
    
    # Give it a moment to start
    sleep 2
    
    # Check status
    if launchctl list | grep -q "com.keeboarder.recorder"; then
        echo -e "${GREEN}✓ LaunchAgent is active${NC}"
        return 0
    else
        echo -e "${RED}✗ LaunchAgent failed to load${NC}"
        return 1
    fi
}

unload_launchagent() {
    echo -e "${YELLOW}Unloading LaunchAgent...${NC}"
    
    if [ -f "$LAUNCHAGENT_PLIST" ]; then
        launchctl unload "$LAUNCHAGENT_PLIST" 2>/dev/null || true
        echo -e "${GREEN}✓ LaunchAgent unloaded${NC}"
    else
        echo -e "${RED}✗ LaunchAgent plist not found: $LAUNCHAGENT_PLIST${NC}"
        return 1
    fi
    
    # Kill any running processes
    pkill -f start_recorder.sh || true
    pkill -f ffmpeg || true
}

restart_launchagent() {
    echo -e "${YELLOW}Restarting LaunchAgent...${NC}"
    unload_launchagent
    sleep 1
    load_launchagent
}

status_launchagent() {
    echo -e "${YELLOW}Checking LaunchAgent status...${NC}"
    echo ""
    
    if launchctl list | grep -q "com.keeboarder.recorder"; then
        echo -e "${GREEN}✓ LaunchAgent is loaded and active${NC}"
    else
        echo -e "${RED}✗ LaunchAgent is not loaded${NC}"
    fi
    
    echo ""
    echo "LaunchAgent details:"
    launchctl list 2>/dev/null | grep -A5 "com.keeboarder.recorder" || echo "  (Not loaded)"
    
    echo ""
    echo "FFmpeg processes:"
    ps aux | grep -E "[f]fmpeg" | awk '{print "  PID " $2 ": " $0}' || echo "  (None running)"
    
    echo ""
    echo "Recorder script processes:"
    ps aux | grep -E "[s]tart_recorder" | awk '{print "  PID " $2 ": " $0}' || echo "  (None running)"
}

create_launchagent_plist() {
    echo -e "${YELLOW}Creating LaunchAgent plist...${NC}"
    
    mkdir -p "$LAUNCHAGENT_DIR"
    
    cat > "$LAUNCHAGENT_PLIST" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
"http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.keeboarder.recorder</string>

    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>@SCRIPTS_DIR@/start_recorder.sh</string>
    </array>

    <key>RunAtLoad</key>
    <true/>

    <key>KeepAlive</key>
    <true/>

    <key>StandardOutPath</key>
    <string>@LOGS_DIR@/launchagent.log</string>

    <key>StandardErrorPath</key>
    <string>@LOGS_DIR@/launchagent_error.log</string>

    <key>WorkingDirectory</key>
    <string>@SCRIPTS_DIR@</string>

    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin</string>
    </dict>
</dict>
</plist>
EOF

    # Replace placeholders
    sed -i '' "s|@SCRIPTS_DIR@|$SCRIPTS_DIR|g" "$LAUNCHAGENT_PLIST"
    sed -i '' "s|@LOGS_DIR@|$LOGS_DIR|g" "$LAUNCHAGENT_PLIST"
    
    echo -e "${GREEN}✓ LaunchAgent plist created:${NC}"
    echo "  $LAUNCHAGENT_PLIST"
}

# ============================================================
# Main Menu
# ============================================================

if [ $# -eq 0 ]; then
    echo -e "${YELLOW}Usage:${NC}"
    echo "  ./setup-launchagent.sh [command]"
    echo ""
    echo -e "${YELLOW}Commands:${NC}"
    echo "  load       - Load/start the LaunchAgent"
    echo "  unload     - Unload/stop the LaunchAgent"
    echo "  restart    - Restart the LaunchAgent"
    echo "  status     - Show current status"
    echo "  create     - Create the plist file (done automatically)"
    echo "  logs       - Show recent log output"
    echo ""
    
    # Show current status
    status_launchagent
    exit 0
fi

# Process command
case "$1" in
    load)
        create_launchagent_plist
        load_launchagent
        ;;
    unload)
        unload_launchagent
        ;;
    restart)
        restart_launchagent
        ;;
    status)
        status_launchagent
        ;;
    create)
        create_launchagent_plist
        ;;
    logs)
        echo -e "${YELLOW}Recent LaunchAgent logs:${NC}"
        echo ""
        if [ -f "$LOGS_DIR/launchagent.log" ]; then
            tail -50 "$LOGS_DIR/launchagent.log"
        else
            echo "No logs found yet"
        fi
        ;;
    *)
        echo -e "${RED}Unknown command: $1${NC}"
        echo "Use './setup-launchagent.sh' for help"
        exit 1
        ;;
esac

echo ""
