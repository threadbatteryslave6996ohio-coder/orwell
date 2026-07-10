#!/bin/bash
set -uo pipefail

STREAM_LOG_DIR="${STREAM_LOG_DIR:-/var/log/streaming}"
STREAM_RECORD_DIR="${STREAM_RECORD_DIR:-/var/lib/streaming/recordings}"
STREAM_SOURCE_URL="${STREAM_SOURCE_URL:-rtsp://127.0.0.1:8554/live}"
STREAM_SEGMENT_SECONDS="${STREAM_SEGMENT_SECONDS:-3600}"
STREAM_SEGMENT_EXTENSION="${STREAM_SEGMENT_EXTENSION:-mp4}"
STREAM_SEGMENT_FORMAT="${STREAM_SEGMENT_FORMAT:-mp4}"
STREAM_SEGMENT_FORMAT_OPTIONS="${STREAM_SEGMENT_FORMAT_OPTIONS:-movflags=+empty_moov+default_base_moof+frag_keyframe}"
STREAM_KEYFRAME_INTERVAL="${STREAM_KEYFRAME_INTERVAL:-15}"
STREAM_INPUT_FORMAT="${STREAM_INPUT_FORMAT:-rtsp}"
STREAM_INPUT_TRANSPORT="${STREAM_INPUT_TRANSPORT:-tcp}"
RECORDER_LOG="$STREAM_LOG_DIR/stream-recorder.log"

mkdir -p "$STREAM_LOG_DIR" "$STREAM_RECORD_DIR"

log_message() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$RECORDER_LOG"
}

cleanup() {
    log_message "INFO" "Stopping stream recorder..."
    pkill -P $$ ffmpeg 2>/dev/null || true
    wait 2>/dev/null || true
    exit 0
}

trap cleanup SIGTERM SIGINT SIGHUP

if ! command -v ffmpeg >/dev/null 2>&1; then
    log_message "ERROR" "ffmpeg not found"
    exit 1
fi

log_message "INFO" "Recorder source: $STREAM_SOURCE_URL"
log_message "INFO" "Recorder destination: $STREAM_RECORD_DIR"

while true; do
    log_message "INFO" "Starting stream recorder..."
    ffmpeg -hide_banner -loglevel info \
        -rtsp_transport "$STREAM_INPUT_TRANSPORT" \
        -i "$STREAM_SOURCE_URL" \
        -map 0:v:0 -map 0:a? \
        -c copy \
        -f segment -segment_time "$STREAM_SEGMENT_SECONDS" -reset_timestamps 1 \
        -segment_format "$STREAM_SEGMENT_FORMAT" \
        -segment_format_options "$STREAM_SEGMENT_FORMAT_OPTIONS" \
        -strftime 1 \
        "$STREAM_RECORD_DIR/live-%Y-%m-%d-%H.$STREAM_SEGMENT_EXTENSION" \
        >> "$RECORDER_LOG" 2>&1

    exit_code=$?

    if [ "$exit_code" -ne 0 ]; then
        log_message "WARN" "Stream recorder exited with code $exit_code. Restarting in 5 seconds..."
        sleep 5
    fi
done
