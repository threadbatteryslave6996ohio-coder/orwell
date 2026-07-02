#!/bin/bash
set -euo pipefail

# Keeboarder Ubuntu GNOME Client - Installation Script

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Keeboarder Ubuntu GNOME Client Installer${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

check_tool() {
    local tool="$1"
    local install_cmd="$2"

    if ! command -v "$tool" >/dev/null 2>&1; then
        echo -e "${RED}x $tool not found${NC}"
        echo "  Install with: $install_cmd"
        return 1
    fi

    echo -e "${GREEN}✓ $tool found${NC}"
}

echo -e "${YELLOW}Checking required tools...${NC}"
all_tools_ok=true
check_tool "ffmpeg" "sudo apt install ffmpeg" || all_tools_ok=false
check_tool "ffprobe" "sudo apt install ffmpeg" || all_tools_ok=false
check_tool "curl" "sudo apt install curl" || all_tools_ok=false
check_tool "pactl" "sudo apt install pulseaudio-utils" || all_tools_ok=false
check_tool "systemctl" "systemd is required on Ubuntu" || all_tools_ok=false

wayland_capture=false
if [ "$CAPTURE_BACKEND" = "gnome-wayland" ] || { [ "$CAPTURE_BACKEND" = "auto" ] && [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; }; then
    wayland_capture=true
    check_tool "gjs" "sudo apt install gjs" || all_tools_ok=false
    check_tool "gst-inspect-1.0" "sudo apt install gstreamer1.0-tools" || all_tools_ok=false
    if command -v gst-inspect-1.0 >/dev/null 2>&1; then
        for plugin in pipewiresrc vp8enc webmmux; do
            if ! gst-inspect-1.0 "$plugin" >/dev/null 2>&1; then
                echo -e "${RED}x GStreamer plugin $plugin not found${NC}"
                all_tools_ok=false
            fi
        done
    fi
else
    check_tool "xdpyinfo" "sudo apt install x11-utils" || all_tools_ok=false
fi
if [ "$UPLOAD_MODE" != "proxy" ]; then
    check_tool "aws" "sudo apt install awscli" || all_tools_ok=false
fi

if [ "$all_tools_ok" = false ]; then
    echo ""
    echo -e "${YELLOW}Install dependencies with:${NC}"
    echo "  sudo apt update"
    echo "  sudo apt install ffmpeg curl pulseaudio-utils x11-utils gjs"
    echo "  sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-good"
    echo "  sudo apt install awscli  # only needed for UPLOAD_MODE=s3"
    exit 1
fi

echo ""
echo -e "${YELLOW}Checking GNOME session...${NC}"
echo "  Desktop: ${XDG_CURRENT_DESKTOP:-unknown}"
echo "  Session type: ${XDG_SESSION_TYPE:-unknown}"

if [ "$wayland_capture" = true ]; then
    case "${XDG_CURRENT_DESKTOP:-}" in
        *GNOME*|*Gnome*|*gnome*|*ubuntu*|*Ubuntu*)
            echo -e "${GREEN}✓ GNOME Wayland capture will use Shell's PipeWire screencast service${NC}"
            ;;
        *)
            echo -e "${RED}x The gnome-wayland backend requires a logged-in GNOME desktop session${NC}"
            exit 1
            ;;
    esac
fi

echo ""
if [ "$UPLOAD_MODE" = "proxy" ]; then
    echo -e "${YELLOW}Checking proxy upload configuration...${NC}"
    if [ -z "$PROXY_URL" ] || [ -z "$PROXY_USERNAME" ] || [ -z "$PROXY_PASSWORD" ]; then
        echo -e "${RED}x PROXY_URL, PROXY_USERNAME, and PROXY_PASSWORD are required when UPLOAD_MODE=proxy${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ Proxy upload configuration present${NC}"
else
    echo -e "${YELLOW}Checking AWS credentials...${NC}"
    if ! aws sts get-caller-identity ${AWS_PROFILE:+--profile "$AWS_PROFILE"} >/dev/null 2>&1; then
        echo -e "${RED}x AWS credentials not configured or invalid${NC}"
        echo "  Configure with: aws configure"
        exit 1
    fi
    identity=$(aws sts get-caller-identity ${AWS_PROFILE:+--profile "$AWS_PROFILE"} --query 'Arn' --output text)
    echo -e "${GREEN}✓ AWS credentials valid${NC}"
    echo "  Account: $identity"

    echo ""
    echo -e "${YELLOW}Checking S3 bucket...${NC}"
    if ! aws s3 ls "s3://$S3_BUCKET" --region "$AWS_REGION" ${AWS_PROFILE:+--profile "$AWS_PROFILE"} >/dev/null 2>&1; then
        echo -e "${RED}x Cannot access S3 bucket: $S3_BUCKET${NC}"
        echo "  Create it or update S3_BUCKET in config.sh."
        exit 1
    fi
    echo -e "${GREEN}✓ S3 bucket accessible${NC}"
fi

echo ""
echo -e "${YELLOW}Checking audio sources...${NC}"
if pactl list short sources | grep -q .; then
    echo -e "${GREEN}✓ Pulse/PipeWire sources available${NC}"
else
    echo -e "${RED}x No audio sources found through pactl${NC}"
    exit 1
fi

if [ "$SYSTEM_AUDIO_SOURCE" = "auto" ]; then
    monitor_source=$(pactl list short sources | awk '$2 ~ /\.monitor$/ {print $2; exit}')
    if [ -n "$monitor_source" ]; then
        echo -e "${GREEN}✓ System audio monitor source found: $monitor_source${NC}"
    else
        echo -e "${YELLOW}! No monitor source found; system audio recording will be skipped until configured${NC}"
    fi
fi

echo ""
echo -e "${YELLOW}Creating directories...${NC}"
mkdir -p "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR" "$LOGS_DIR" "$SCRIPTS_DIR"
echo -e "${GREEN}✓ Directories created under $RECORDINGS_DIR${NC}"

echo ""
echo -e "${YELLOW}Installing scripts to $SCRIPTS_DIR...${NC}"
install -m 755 "$SCRIPT_DIR/start_recorder.sh" "$SCRIPTS_DIR/start_recorder.sh"
install -m 755 "$SCRIPT_DIR/gnome_wayland_recorder.js" "$SCRIPTS_DIR/gnome_wayland_recorder.js"
install -m 755 "$SCRIPT_DIR/upload_to_s3.sh" "$SCRIPTS_DIR/upload_to_s3.sh"
install -m 755 "$SCRIPT_DIR/sync_videos.sh" "$SCRIPTS_DIR/sync_videos.sh"
install -m 755 "$SCRIPT_DIR/verify_uploads.sh" "$SCRIPTS_DIR/verify_uploads.sh"
install -m 755 "$SCRIPT_DIR/setup-systemd.sh" "$SCRIPTS_DIR/setup-systemd.sh"
install -m 644 "$SCRIPT_DIR/config.sh" "$SCRIPTS_DIR/config.sh"
echo -e "${GREEN}✓ Scripts installed${NC}"

echo ""
echo -e "${YELLOW}Creating and starting systemd user services...${NC}"
"$SCRIPTS_DIR/setup-systemd.sh" load
echo -e "${GREEN}✓ systemd user service and timers configured${NC}"

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}Installation Complete${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Configuration:"
echo "  S3 Bucket: $S3_BUCKET"
echo "  AWS Region: $AWS_REGION"
echo "  Upload Mode: $UPLOAD_MODE"
echo "  Recordings: $RECORDINGS_DIR"
echo "  Scripts: $SCRIPTS_DIR"
echo ""
echo "Useful commands:"
echo "  ./manage.sh status"
echo "  ./manage.sh logs"
echo "  ./manage.sh upload"
echo "  ./manage.sh sync"
echo ""
