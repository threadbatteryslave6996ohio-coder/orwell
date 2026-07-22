#!/bin/bash
# Configuration file for Keeboarder macOS recorder
# Customize these settings before running install.sh

# ============================================================
# Recording Directories
# ============================================================

# Base directory for all recordings (will be created if doesn't exist)
RECORDINGS_DIR="$HOME/recordings"

# Individual subdirectories
SCREEN_DIR="$RECORDINGS_DIR/screen"
MIC_DIR="$RECORDINGS_DIR/mic"
SYSTEM_AUDIO_DIR="$RECORDINGS_DIR/system-audio"
LOGS_DIR="$RECORDINGS_DIR/logs"

# Scripts directory
SCRIPTS_DIR="$HOME/scripts"

# ============================================================
# FFmpeg Device Names
# ============================================================

# Run this to find your exact device names:
# ffmpeg -f avfoundation -list_devices true -i ""

# Screen capture device
SCREEN_DEVICE="Capture screen 0"

# Microphone device (verify with ffmpeg command above)
MIC_DEVICE="MacBook Microphone"

# System audio device (BlackHole)
SYSTEM_AUDIO_DEVICE="BlackHole 2ch"

# ============================================================
# FFmpeg Recording Settings
# ============================================================

# Screen recording: frames per second
SCREEN_FPS="15"

# Screen recording: quality (CRF - lower = better, 0-51)
# 28 is good balance between quality and file size
SCREEN_CRF="28"

# Screen recording: encoder preset (ultrafast, superfast, veryfast, faster, fast, medium, slow, slower, veryslow)
SCREEN_PRESET="ultrafast"

# Screen recording: fragmented MP4 segment settings for crash resilience
SCREEN_SEGMENT_EXTENSION="mp4"
SCREEN_SEGMENT_FORMAT="mp4"
SCREEN_SEGMENT_FORMAT_OPTIONS="movflags=+empty_moov+default_base_moof+frag_keyframe"
SCREEN_KEYFRAME_INTERVAL="15"

# Segment duration in seconds (how long each file will be)
# 3600 = 1 hour
SEGMENT_DURATION="3600"

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
REMOTE_STREAM_KEYFRAME_INTERVAL="$SCREEN_KEYFRAME_INTERVAL"

# ============================================================
# Logging Settings
# ============================================================

# Log level (DEBUG, INFO, WARN, ERROR)
LOG_LEVEL="INFO"

# Keep local logs for how many days (0 = keep all)
LOG_RETENTION_DAYS="30"

# ============================================================
# Advanced Settings
# ============================================================

# Parallel FFmpeg processes (use with caution, impacts CPU)
PARALLEL_FFMPEG="3"

# Enable verbose output for debugging
DEBUG_MODE="false"

# Device hostname used to label this machine's recordings (auto-detected if not set)
DEVICE_HOSTNAME="$(hostname)"

# ============================================================
# LaunchAgent Settings
# ============================================================

# Service label for LaunchAgent
LAUNCHAGENT_LABEL="com.keeboarder.recorder"

# Restart interval if service crashes (seconds)
RESTART_INTERVAL="60"

# ============================================================
# Export all for use in scripts
# ============================================================

export RECORDINGS_DIR
export SCREEN_DIR
export MIC_DIR
export SYSTEM_AUDIO_DIR
export LOGS_DIR
export SCRIPTS_DIR
export SCREEN_DEVICE
export MIC_DEVICE
export SYSTEM_AUDIO_DEVICE
export SCREEN_FPS
export SCREEN_CRF
export SCREEN_PRESET
export SCREEN_SEGMENT_EXTENSION
export SCREEN_SEGMENT_FORMAT
export SCREEN_SEGMENT_FORMAT_OPTIONS
export SCREEN_KEYFRAME_INTERVAL
export SEGMENT_DURATION
export REMOTE_STREAM_ENABLED
export REMOTE_STREAM_URL
export REMOTE_STREAM_FORMAT
export REMOTE_STREAM_CODEC
export REMOTE_STREAM_PRESET
export REMOTE_STREAM_CRF
export REMOTE_STREAM_PIXEL_FORMAT
export REMOTE_STREAM_TUNE
export REMOTE_STREAM_KEYFRAME_INTERVAL
export LOG_LEVEL
export LOG_RETENTION_DAYS
export PARALLEL_FFMPEG
export DEBUG_MODE
export DEVICE_HOSTNAME
export LAUNCHAGENT_LABEL
export RESTART_INTERVAL
