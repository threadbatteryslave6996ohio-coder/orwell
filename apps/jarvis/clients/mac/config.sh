#!/bin/bash
# Configuration file for Keeboarder macOS recorder
# Customize these settings before running install.sh

# ============================================================
# AWS S3 Configuration
# ============================================================

# S3 Bucket name (where recordings will be uploaded)
S3_BUCKET="keeboarder-recordings"

# AWS region
AWS_REGION="us-east-1"

# S3 path prefix for this machine (optional, for organization)
# Leave empty to store in bucket root, or use "recordings/" for a folder
S3_PREFIX="recordings/"

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
# Upload Settings
# ============================================================

# Minimum file age in seconds before uploading (to avoid uploading incomplete files)
# Set to 5 seconds to allow time for file to be closed
MIN_FILE_AGE="5"

# Used only when neither lsof nor fuser is available.
FILE_STABILITY_WAIT_SECONDS="3"

# Upload batch size (number of files to upload at once)
UPLOAD_BATCH_SIZE="10"

# Delete local files after successful upload (true/false)
DELETE_AFTER_UPLOAD="false"

# Check the remote object and size before uploading a completed video.
CHECK_REMOTE_BEFORE_UPLOAD="true"

# ============================================================
# Verification Settings
# ============================================================

# Check that S3 files match local file sizes
VERIFY_FILE_SIZE="true"

# Email for alerts (optional, leave empty to disable)
# Requires mail configured on system
ALERT_EMAIL=""

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

# AWS CLI profile to use (leave empty for default)
AWS_PROFILE=""

# Enable verbose output for debugging
DEBUG_MODE="false"

# Device hostname for S3 metadata (auto-detected if not set)
DEVICE_HOSTNAME="$(hostname)"

# ============================================================
# LaunchAgent Settings
# ============================================================

# Service label for LaunchAgent
LAUNCHAGENT_LABEL="com.keeboarder.recorder"

# Restart interval if service crashes (seconds)
RESTART_INTERVAL="60"

# ============================================================
# Cron Job Settings (for uploads and verification)
# ============================================================

# Upload frequency (cron format)
# Examples: "*/15 * * * *" = every 15 minutes
#           "0 * * * *" = every hour
#           "0 */6 * * *" = every 6 hours
UPLOAD_CRON="*/15 * * * *"

# Verification frequency (cron format)
# Example: "0 * * * *" = every hour
VERIFY_CRON="0 * * * *"

# Cleanup frequency (cron format - removes old local files)
# Example: "0 3 * * *" = daily at 3 AM
CLEANUP_CRON="0 3 * * *"

# ============================================================
# Export all for use in scripts
# ============================================================

export S3_BUCKET
export AWS_REGION
export S3_PREFIX
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
export MIN_FILE_AGE
export FILE_STABILITY_WAIT_SECONDS
export UPLOAD_BATCH_SIZE
export DELETE_AFTER_UPLOAD
export CHECK_REMOTE_BEFORE_UPLOAD
export VERIFY_FILE_SIZE
export ALERT_EMAIL
export LOG_LEVEL
export LOG_RETENTION_DAYS
export PARALLEL_FFMPEG
export AWS_PROFILE
export DEBUG_MODE
export DEVICE_HOSTNAME
export LAUNCHAGENT_LABEL
export RESTART_INTERVAL
