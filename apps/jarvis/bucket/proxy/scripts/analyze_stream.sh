#!/bin/bash
set -uo pipefail

STREAM_LOG_DIR="${STREAM_LOG_DIR:-/var/log/streaming}"
STREAM_SOURCE_URL="${STREAM_ANALYSIS_SOURCE_URL:-rtsp://127.0.0.1:8554/live}"
STREAM_ANALYSIS_FPS="${STREAM_ANALYSIS_FPS:-}"
STREAM_ANALYSIS_WIDTH="${STREAM_ANALYSIS_WIDTH:-640}"
STREAM_ANALYSIS_ENDPOINT="${STREAM_ANALYSIS_ENDPOINT:-}"
ANALYSIS_LOG="$STREAM_LOG_DIR/stream-analysis.log"

mkdir -p "$STREAM_LOG_DIR"

log_message() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$ANALYSIS_LOG"
}

cleanup() {
    log_message "INFO" "Stopping stream analyzer..."
    pkill -P $$ ffmpeg 2>/dev/null || true
    pkill -P $$ java 2>/dev/null || true
    wait 2>/dev/null || true
    exit 0
}

trap cleanup SIGTERM SIGINT SIGHUP

if ! command -v ffmpeg >/dev/null 2>&1; then
    log_message "ERROR" "ffmpeg not found"
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    log_message "ERROR" "java not found"
    exit 1
fi

# The stream worker is a mode of the jarvis-bucket-proxy jar. Launch the jar with
# --mode=stream-worker to run the analysis worker instead of the web server.
# STREAM_ANALYSIS_WORKER_MODE overrides the mode flag for non-default builds.
JAVA_WORKER_JAR="${STREAM_ANALYSIS_WORKER_JAR:-/opt/s3-proxy/publish/jarvis-bucket-proxy.jar}"
# Split on whitespace so a multi-flag override (e.g. "--mode=stream-worker --debug") works.
read -r -a WORKER_MODE_ARGS <<< "${STREAM_ANALYSIS_WORKER_MODE:---mode=stream-worker}"

if [ -z "$STREAM_ANALYSIS_ENDPOINT" ]; then
    log_message "WARN" "STREAM_ANALYSIS_ENDPOINT is empty; frames will be sampled but not posted"
fi

log_message "INFO" "Analyzer source: $STREAM_SOURCE_URL"
log_message "INFO" "Analyzer endpoint: ${STREAM_ANALYSIS_ENDPOINT:-disabled}"

while true; do
    log_message "INFO" "Starting stream analyzer..."
    set -o pipefail
    if [ -n "$STREAM_ANALYSIS_FPS" ]; then
        ffmpeg -hide_banner -loglevel warning \
            -rtsp_transport tcp \
            -i "$STREAM_SOURCE_URL" \
            -vf "fps=$STREAM_ANALYSIS_FPS,scale=$STREAM_ANALYSIS_WIDTH:-2" \
            -q:v 3 \
            -f image2pipe -vcodec mjpeg - \
            2>>"$ANALYSIS_LOG" | \
            STREAM_ANALYSIS_ENDPOINT="$STREAM_ANALYSIS_ENDPOINT" \
            java -jar "$JAVA_WORKER_JAR" "${WORKER_MODE_ARGS[@]}" >> "$ANALYSIS_LOG" 2>&1
        else
        ffmpeg -hide_banner -loglevel warning \
            -rtsp_transport tcp \
            -i "$STREAM_SOURCE_URL" \
            -vf "scale=$STREAM_ANALYSIS_WIDTH:-2" \
            -q:v 3 \
            -f image2pipe -vcodec mjpeg - \
            2>>"$ANALYSIS_LOG" | \
            STREAM_ANALYSIS_ENDPOINT="$STREAM_ANALYSIS_ENDPOINT" \
            java -jar "$JAVA_WORKER_JAR" "${WORKER_MODE_ARGS[@]}" >> "$ANALYSIS_LOG" 2>&1
        fi

    exit_code=${PIPESTATUS[0]}

    if [ "$exit_code" -ne 0 ]; then
        log_message "WARN" "Stream analyzer exited with code $exit_code. Restarting in 5 seconds..."
        sleep 5
    fi
done
