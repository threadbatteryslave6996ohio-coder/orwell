#!/bin/bash
set -uo pipefail

# Keeboarder macOS FFmpeg Recorder
# Records screen, microphone, and system audio to separate files
# Runs continuously in background via LaunchAgent

# Source configuration
CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$CONFIG_DIR/config.sh"

# Initialize logging
RECORDER_LOG="$LOGS_DIR/recorder.log"
PROCESS_PIDS=()

# Create directories if they don't exist
mkdir -p "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR" "$LOGS_DIR"

# Logging function
log_message() {
    local level="$1"
    local message="$2"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$RECORDER_LOG"
}

# Cleanup function - stop all FFmpeg processes
cleanup() {
    log_message "INFO" "Shutting down recorder gracefully..."
    for pid in "${PROCESS_PIDS[@]:-}"; do
        kill "$pid" 2>/dev/null || true
    done
    wait
    log_message "INFO" "Recorder stopped"
    exit 0
}

# Trap signals for graceful shutdown
trap cleanup SIGTERM SIGINT SIGHUP

# Verify FFmpeg is installed
if ! command -v ffmpeg &> /dev/null; then
    log_message "ERROR" "FFmpeg not found. Please install: brew install ffmpeg"
    exit 1
fi

# Verify devices exist (optional check - comment out if causing issues)
check_devices() {
    ffmpeg -f avfoundation -list_devices true -i "" 2>&1 | grep -q "$SCREEN_DEVICE" || \
        log_message "WARN" "Screen device '$SCREEN_DEVICE' not found in device list"
    ffmpeg -f avfoundation -list_devices true -i "" 2>&1 | grep -q "$MIC_DEVICE" || \
        log_message "WARN" "Microphone device '$MIC_DEVICE' not found in device list"
    ffmpeg -f avfoundation -list_devices true -i "" 2>&1 | grep -q "$SYSTEM_AUDIO_DEVICE" || \
        log_message "WARN" "System audio device '$SYSTEM_AUDIO_DEVICE' not found in device list"
}

log_message "INFO" "=== Keeboarder Recorder Starting ==="
log_message "INFO" "Screen device: $SCREEN_DEVICE"
log_message "INFO" "Microphone device: $MIC_DEVICE"
log_message "INFO" "System audio device: $SYSTEM_AUDIO_DEVICE"
log_message "INFO" "Recording directory: $RECORDINGS_DIR"
if [ "$REMOTE_STREAM_ENABLED" = "true" ] && [ -n "$REMOTE_STREAM_URL" ]; then
    log_message "INFO" "Remote stream enabled: $REMOTE_STREAM_FORMAT -> $REMOTE_STREAM_URL"
elif [ "$REMOTE_STREAM_ENABLED" = "true" ]; then
    log_message "WARN" "Remote stream is enabled but REMOTE_STREAM_URL is empty; local recording will continue independently"
else
    log_message "INFO" "Remote stream disabled"
fi
log_message "DEBUG" "Checking devices..."

# Check devices (optional, disable if causing startup issues)
if [ "$DEBUG_MODE" = "true" ]; then
    check_devices
fi

log_message "INFO" "Starting FFmpeg processes..."

# Function to start FFmpeg process with restart capability
start_ffmpeg_process() {
    local name="$1"
    local input="$2"
    shift 2

    (
        current_pid=""
        cleanup_child() {
            if [ -n "$current_pid" ]; then
                kill "$current_pid" 2>/dev/null || true
            fi
        }
        trap cleanup_child EXIT
        trap 'cleanup_child; exit 0' SIGTERM SIGINT SIGHUP

        while true; do
            log_message "INFO" "Starting $name process..."

            ffmpeg -nostdin -f avfoundation -i "$input" "$@" >> "$RECORDER_LOG" 2>&1 &
            local pid=$!
            current_pid="$pid"
            log_message "DEBUG" "$name process started with PID $pid"

            # Wait for process (if it exits, restart)
            wait "$pid"
            local exit_code=$?
            current_pid=""

            if [ "$exit_code" -ne 0 ]; then
                log_message "WARN" "$name process exited with code $exit_code. Restarting in 5 seconds..."
                sleep 5
            fi
        done
    ) &
    PROCESS_PIDS+=("$!")
}

# Start recording processes in background with their own restart loops

# Screen recording: MP4 with H.264 compression
log_message "INFO" "Starting screen recording (MP4, H.264, $SCREEN_FPS fps, CRF $SCREEN_CRF)..."
start_ffmpeg_process "screen" \
    "$SCREEN_DEVICE:none" \
    -framerate "$SCREEN_FPS" -c:v libx264 -preset "$SCREEN_PRESET" -crf "$SCREEN_CRF" \
    -g "$SCREEN_KEYFRAME_INTERVAL" -keyint_min "$SCREEN_KEYFRAME_INTERVAL" -sc_threshold 0 \
    -f segment -segment_time "$SEGMENT_DURATION" -reset_timestamps 1 \
    -segment_format "$SCREEN_SEGMENT_FORMAT" \
    -segment_format_options "$SCREEN_SEGMENT_FORMAT_OPTIONS" \
    -flush_packets 1 -strftime 1 \
    "$SCREEN_DIR/screen-%Y-%m-%d-%H.$SCREEN_SEGMENT_EXTENSION"

if [ "$REMOTE_STREAM_ENABLED" = "true" ] && [ -n "$REMOTE_STREAM_URL" ]; then
    log_message "INFO" "Starting remote screen stream ($REMOTE_STREAM_FORMAT)..."
    start_ffmpeg_process "screen-stream" \
        "$SCREEN_DEVICE:none" \
        -framerate "$SCREEN_FPS" \
        -c:v "$REMOTE_STREAM_CODEC" -preset "$REMOTE_STREAM_PRESET" -crf "$REMOTE_STREAM_CRF" \
        -pix_fmt "$REMOTE_STREAM_PIXEL_FORMAT" -tune "$REMOTE_STREAM_TUNE" \
        -g "$REMOTE_STREAM_KEYFRAME_INTERVAL" -keyint_min "$REMOTE_STREAM_KEYFRAME_INTERVAL" -sc_threshold 0 \
        -f "$REMOTE_STREAM_FORMAT" "$REMOTE_STREAM_URL"
fi

# Microphone recording: WAV format
log_message "INFO" "Starting microphone recording (WAV)..."
start_ffmpeg_process "microphone" \
    ":$MIC_DEVICE" \
    -acodec pcm_s16le \
    -f segment -segment_time "$SEGMENT_DURATION" -strftime 1 \
    "$MIC_DIR/mic-%Y-%m-%d-%H.wav"

# System audio recording: WAV format (through BlackHole)
log_message "INFO" "Starting system audio recording (WAV via BlackHole)..."
start_ffmpeg_process "system-audio" \
    ":$SYSTEM_AUDIO_DEVICE" \
    -acodec pcm_s16le \
    -f segment -segment_time "$SEGMENT_DURATION" -strftime 1 \
    "$SYSTEM_AUDIO_DIR/system-%Y-%m-%d-%H.wav"

log_message "INFO" "All recording processes started. Monitoring..."

# Keep the parent process alive
# This allows us to catch signals and perform graceful shutdown
wait
