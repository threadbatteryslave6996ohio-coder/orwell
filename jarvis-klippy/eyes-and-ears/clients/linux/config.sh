#!/bin/bash
# Configuration file for Keeboarder Ubuntu GNOME recorder.
# Customize these settings before running install.sh.

# ============================================================
# AWS S3 Configuration
# ============================================================

S3_BUCKET="keeboarder-recordings"
AWS_REGION="us-east-1"
S3_PREFIX="recordings/"

# Upload mode:
# - s3: direct AWS CLI upload
# - proxy: upload through bucket/proxy using auth-server clientId/secret auth
UPLOAD_MODE="proxy"
PROXY_URL="http://localhost:5000"
PROXY_USERNAME="d"
PROXY_PASSWORD="d"

# ============================================================
# Recording Directories
# ============================================================

RECORDINGS_DIR="$HOME/recordings"
SCREEN_DIR="$RECORDINGS_DIR/screen"
MIC_DIR="$RECORDINGS_DIR/mic"
SYSTEM_AUDIO_DIR="$RECORDINGS_DIR/system-audio"
LOGS_DIR="$RECORDINGS_DIR/logs"
SCRIPTS_DIR="$HOME/scripts"
PROXY_TOKEN_FILE="$LOGS_DIR/.proxy_token"

# ============================================================
# Ubuntu GNOME Capture Settings
# ============================================================

# Capture backend: auto, x11, or gnome-wayland. Auto selects GNOME Shell's
# PipeWire-backed screencast service for GNOME Wayland and x11grab otherwise.
CAPTURE_BACKEND="auto"

# Legacy compatibility switch. Set true only to reject Wayland explicitly.
REQUIRE_XORG="false"

# X11 display to capture. Usually ":0.0" or ":1.0".
DISPLAY_NAME="${DISPLAY:-:0.0}"

# Capture geometry. Leave empty to auto-detect with xdpyinfo.
# Example: "1920x1080"
SCREEN_SIZE=""

# Capture origin.
SCREEN_OFFSET_X="0"
SCREEN_OFFSET_Y="0"

# GNOME Wayland uses the Shell screencast service, whose reliable native output
# is WebM. Screen segments from both backends are accepted by the uploader.
WAYLAND_SCREEN_EXTENSION="webm"
WAYLAND_DRAW_CURSOR="true"

# PulseAudio/PipeWire Pulse source names.
# Use `pactl list short sources` to find exact values.
# Typical microphone default: "@DEFAULT_SOURCE@"
MIC_SOURCE="@DEFAULT_SOURCE@"

# Typical system-audio monitor source can be auto-detected.
# Example: "alsa_output.pci-0000_00_1f.3.analog-stereo.monitor"
SYSTEM_AUDIO_SOURCE="auto"

# ============================================================
# FFmpeg Recording Settings
# ============================================================

SCREEN_FPS="15"
SCREEN_CRF="28"
SCREEN_PRESET="ultrafast"
SEGMENT_DURATION="3600"

# Make the current in-progress video segment resilient to sudden power loss.
# Fragmented MP4 writes playable metadata throughout the file instead of only
# finalizing it at shutdown.
SCREEN_SEGMENT_EXTENSION="mp4"
SCREEN_SEGMENT_FORMAT="mp4"
SCREEN_SEGMENT_FORMAT_OPTIONS="movflags=+empty_moov+default_base_moof+frag_keyframe"
SCREEN_KEYFRAME_INTERVAL="15"

# ============================================================
# Upload Settings
# ============================================================

MIN_FILE_AGE="5"
FILE_STABILITY_WAIT_SECONDS="3"
UPLOAD_BATCH_SIZE="10"
DELETE_AFTER_UPLOAD="false"
VALIDATE_MEDIA_BEFORE_UPLOAD="true"
CHECK_REMOTE_BEFORE_UPLOAD="true"

# ============================================================
# Remote Streaming Settings
# ============================================================

# Optional live stream of the screen capture to a remote ingest endpoint.
# Examples:
# - rtmp://stream.example.com/live
# - srt://stream.example.com:9000?mode=caller
# Streaming starts automatically once REMOTE_STREAM_URL is configured. An empty
# URL never prevents the independent local recorder from starting.
REMOTE_STREAM_ENABLED="true"
REMOTE_STREAM_URL=""
REMOTE_STREAM_FORMAT="flv"
REMOTE_STREAM_CODEC="libx264"
REMOTE_STREAM_PRESET="veryfast"
REMOTE_STREAM_CRF="30"
REMOTE_STREAM_PIXEL_FORMAT="yuv420p"
REMOTE_STREAM_TUNE="zerolatency"
REMOTE_STREAM_KEYFRAME_INTERVAL="15"

# ============================================================
# Verification Settings
# ============================================================

VERIFY_FILE_SIZE="true"
ALERT_EMAIL=""

# ============================================================
# Logging Settings
# ============================================================

LOG_LEVEL="INFO"
LOG_RETENTION_DAYS="30"

# ============================================================
# Advanced Settings
# ============================================================

AWS_PROFILE=""
DEBUG_MODE="false"
DEVICE_HOSTNAME="$(hostname)"

# ============================================================
# systemd User Service Settings
# ============================================================

SYSTEMD_SERVICE_NAME="keeboarder-recorder"
UPLOAD_TIMER_NAME="keeboarder-upload"
VERIFY_TIMER_NAME="keeboarder-verify"
UPLOAD_INTERVAL="15min"
VERIFY_INTERVAL="1h"

export S3_BUCKET AWS_REGION S3_PREFIX UPLOAD_MODE PROXY_URL PROXY_USERNAME PROXY_PASSWORD PROXY_TOKEN_FILE
export RECORDINGS_DIR SCREEN_DIR MIC_DIR SYSTEM_AUDIO_DIR LOGS_DIR SCRIPTS_DIR
export CAPTURE_BACKEND REQUIRE_XORG DISPLAY_NAME SCREEN_SIZE SCREEN_OFFSET_X SCREEN_OFFSET_Y
export WAYLAND_SCREEN_EXTENSION WAYLAND_DRAW_CURSOR
export MIC_SOURCE SYSTEM_AUDIO_SOURCE
export SCREEN_FPS SCREEN_CRF SCREEN_PRESET SEGMENT_DURATION
export SCREEN_SEGMENT_EXTENSION SCREEN_SEGMENT_FORMAT SCREEN_SEGMENT_FORMAT_OPTIONS SCREEN_KEYFRAME_INTERVAL
export MIN_FILE_AGE FILE_STABILITY_WAIT_SECONDS UPLOAD_BATCH_SIZE DELETE_AFTER_UPLOAD VALIDATE_MEDIA_BEFORE_UPLOAD CHECK_REMOTE_BEFORE_UPLOAD
export REMOTE_STREAM_ENABLED REMOTE_STREAM_URL REMOTE_STREAM_FORMAT REMOTE_STREAM_CODEC
export REMOTE_STREAM_PRESET REMOTE_STREAM_CRF REMOTE_STREAM_PIXEL_FORMAT REMOTE_STREAM_TUNE
export REMOTE_STREAM_KEYFRAME_INTERVAL
export VERIFY_FILE_SIZE ALERT_EMAIL
export LOG_LEVEL LOG_RETENTION_DAYS
export AWS_PROFILE DEBUG_MODE DEVICE_HOSTNAME
export SYSTEMD_SERVICE_NAME UPLOAD_TIMER_NAME VERIFY_TIMER_NAME UPLOAD_INTERVAL VERIFY_INTERVAL
