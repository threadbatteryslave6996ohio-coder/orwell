#!/bin/bash

# Keeboarder macOS Client - Installation Script
# Sets up the FFmpeg recorder (record-only client)

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
echo -e "${BLUE}Keeboarder macOS Client Installer${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# ============================================================
# Check for required tools
# ============================================================

echo -e "${YELLOW}Checking for required tools...${NC}"

check_tool() {
    local tool=$1
    local install_cmd=$2

    if ! command -v "$tool" &> /dev/null; then
        echo -e "${RED}✗ $tool not found${NC}"
        echo "  Install with: $install_cmd"
        return 1
    else
        echo -e "${GREEN}✓ $tool found${NC}"
        return 0
    fi
}

all_tools_ok=true

check_tool "ffmpeg" "brew install ffmpeg" || all_tools_ok=false
check_tool "bash" "brew install bash" || all_tools_ok=false

if [ "$all_tools_ok" = false ]; then
    echo ""
    echo -e "${RED}Please install missing tools and run this script again.${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}✓ All required tools found${NC}"

# ============================================================
# Check FFmpeg Devices
# ============================================================

echo ""
echo -e "${YELLOW}Checking FFmpeg devices...${NC}"

DEVICES_OUTPUT=$(ffmpeg -f avfoundation -list_devices true -i "" 2>&1)

check_device() {
    local device_name=$1
    if echo "$DEVICES_OUTPUT" | grep -q "$device_name"; then
        echo -e "${GREEN}✓ Found: $device_name${NC}"
        return 0
    else
        echo -e "${RED}✗ Not found: $device_name${NC}"
        return 1
    fi
}

devices_ok=true
check_device "$SCREEN_DEVICE" || devices_ok=false
check_device "$MIC_DEVICE" || devices_ok=false
check_device "$SYSTEM_AUDIO_DEVICE" || devices_ok=false

if [ "$devices_ok" = false ]; then
    echo ""
    echo -e "${YELLOW}Available devices:${NC}"
    echo "$DEVICES_OUTPUT" | grep -E "^\[|^    \["
fi

# ============================================================
# Create Directories
# ============================================================

echo ""
echo -e "${YELLOW}Creating directories...${NC}"

mkdir -p "$SCREEN_DIR"
mkdir -p "$MIC_DIR"
mkdir -p "$SYSTEM_AUDIO_DIR"
mkdir -p "$LOGS_DIR"
mkdir -p "$SCRIPTS_DIR"

echo -e "${GREEN}✓ Directories created:${NC}"
echo "  $SCREEN_DIR"
echo "  $MIC_DIR"
echo "  $SYSTEM_AUDIO_DIR"
echo "  $LOGS_DIR"

# ============================================================
# Copy Scripts to ~/scripts
# ============================================================

echo ""
echo -e "${YELLOW}Installing scripts to $SCRIPTS_DIR...${NC}"

cp "$SCRIPT_DIR/start_recorder.sh" "$SCRIPTS_DIR/start_recorder.sh"
cp "$SCRIPT_DIR/setup-launchagent.sh" "$SCRIPTS_DIR/setup-launchagent.sh"
cp "$SCRIPT_DIR/config.sh" "$SCRIPTS_DIR/config.sh"

chmod +x "$SCRIPTS_DIR/start_recorder.sh"
chmod +x "$SCRIPTS_DIR/setup-launchagent.sh"

echo -e "${GREEN}✓ Scripts installed and made executable:${NC}"
echo "  $SCRIPTS_DIR/start_recorder.sh"
echo "  $SCRIPTS_DIR/setup-launchagent.sh"

# ============================================================
# Setup LaunchAgent
# ============================================================

echo ""
echo -e "${YELLOW}Setting up LaunchAgent for auto-start...${NC}"

"$SCRIPTS_DIR/setup-launchagent.sh" create

# ============================================================
# Summary and Next Steps
# ============================================================

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}Installation Complete!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

echo -e "${YELLOW}Next Steps:${NC}"
echo ""
echo "1. Grant system permissions:"
echo "   System Settings → Privacy & Security"
echo "   - Screen Recording: Add Terminal/iTerm2"
echo "   - Microphone: Add Terminal/iTerm2"
echo "   - Full Disk Access: Add Terminal/iTerm2"
echo ""

echo "2. (Optional) Verify device names:"
echo "   ffmpeg -f avfoundation -list_devices true -i \"\""
echo "   If your devices differ, update:"
echo "   $SCRIPT_DIR/config.sh"
echo ""

echo "3. Start recording:"
echo "   launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist"
echo ""

echo "4. Check status:"
echo "   launchctl list | grep keeboarder"
echo "   ps aux | grep ffmpeg"
echo ""

echo "5. View logs:"
echo "   tail -f $LOGS_DIR/recorder.log"
echo ""

echo "6. Upload recordings:"
echo "   Recordings are uploaded by the separate syncer client at"
echo "   apps/jarvis/clients/syncer/ (run it on a timer alongside the recorder)."
echo ""

echo -e "${YELLOW}Configuration:${NC}"
echo "  Base directory: $RECORDINGS_DIR"
echo "  Scripts directory: $SCRIPTS_DIR"
echo ""

echo -e "${YELLOW}Important:${NC}"
echo "  - Ensure you have screen recording permissions enabled"
echo "  - Monitor logs for any errors"
echo ""

echo -e "${BLUE}For more info, see: $SCRIPT_DIR/README.md${NC}"
