#!/bin/bash
# Everything the mac and linux recorders do identically.
#
# Sourced, never executed. The caller sets CLIENT_DIR to the directory holding its config.sh and
# then sources this file, which reads that config, prepares the recording directories, installs the
# shutdown trap, and defines the process supervision both platforms run their captures under.
#
# What is deliberately NOT here is capture itself: what a screen is, where audio comes from, and
# how a device is named are the only things that genuinely differ between macOS (avfoundation) and
# Ubuntu (x11grab or GNOME Shell's PipeWire screencast). Each platform's start_recorder.sh keeps
# that and nothing else.
#
# Bash 3.2 compatible, because that is what macOS ships as /bin/bash — no associative arrays, no
# mapfile, no ${var,,}.

if [ -z "${CLIENT_DIR:-}" ]; then
    echo "recorder.sh: CLIENT_DIR must be set to the directory holding config.sh before sourcing." >&2
    exit 1
fi

# shellcheck source=/dev/null
source "$CLIENT_DIR/config.sh"

RECORDER_LOG="$LOGS_DIR/recorder.log"
PROCESS_PIDS=()

mkdir -p "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR" "$LOGS_DIR"

log_message() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$RECORDER_LOG"
}

# Stops every supervised capture and exits. Registered below for the signals a service manager
# uses, so a `systemctl --user stop` or `launchctl unload` ends recording rather than orphaning
# ffmpeg processes that keep writing.
recorder_shutdown() {
    log_message "INFO" "Shutting down recorder gracefully..."
    for pid in "${PROCESS_PIDS[@]:-}"; do
        kill "$pid" 2>/dev/null || true
    done
    wait 2>/dev/null || true
    log_message "INFO" "Recorder stopped"
    exit 0
}

trap recorder_shutdown SIGTERM SIGINT SIGHUP

# Exits with an install hint rather than letting the first capture fail cryptically minutes later.
require_command() {
    local tool="$1"
    local install_hint="$2"
    if ! command -v "$tool" >/dev/null 2>&1; then
        log_message "ERROR" "$tool not found. $install_hint"
        exit 1
    fi
}

# ============================================================
# Process supervision
# ============================================================

# Runs one capture under a restart loop in a background subshell, recording that subshell's pid in
# PROCESS_PIDS so recorder_shutdown can stop it:
#
#     supervise screen ffmpeg -nostdin -f x11grab -i :0.0 ...
#
# A capture that exits non-zero is restarted after five seconds; one that exits cleanly (a segment
# finishing) restarts immediately. The child's pid is tracked inside the subshell so a signal
# reaches ffmpeg itself and not only the loop around it — and the trap *waits* for it, because
# ffmpeg finalizes the segment it is writing on SIGTERM and exiting before it finishes is how the
# last hour of video ends up truncated.
supervise() {
    local name="$1"
    shift
    _supervise_loop "$name" "" "$@" &
    PROCESS_PIDS+=("$!")
}

# As supervise, but asks a function for the command line before every attempt instead of taking a
# fixed one. GNOME Shell's screencast helper records a single segment per run into a filename
# stamped with the time it started, so its command genuinely changes each time round the loop.
# The callback fills the RECORDER_COMMAND array.
supervise_building() {
    local name="$1"
    local builder="$2"
    _supervise_loop "$name" "$builder" &
    PROCESS_PIDS+=("$!")
}

_supervise_loop() {
    local name="$1"
    local builder="$2"
    shift 2
    local -a command
    command=("$@")

    current_pid=""
    # shellcheck disable=SC2329  # invoked indirectly, by the two traps below
    cleanup_child() {
        if [ -n "$current_pid" ]; then
            kill "$current_pid" 2>/dev/null || true
            wait "$current_pid" 2>/dev/null || true
        fi
    }
    trap cleanup_child EXIT
    trap 'cleanup_child; exit 0' SIGTERM SIGINT SIGHUP

    while true; do
        if [ -n "$builder" ]; then
            RECORDER_COMMAND=()
            "$builder"
            command=("${RECORDER_COMMAND[@]}")
        fi

        log_message "INFO" "Starting $name process..."
        "${command[@]}" >> "$RECORDER_LOG" 2>&1 &
        current_pid=$!
        log_message "DEBUG" "$name process started with PID $current_pid"

        wait "$current_pid"
        local exit_code=$?
        current_pid=""

        if [ "$exit_code" -ne 0 ]; then
            log_message "WARN" "$name process exited with code $exit_code. Restarting in 5 seconds..."
            sleep 5
        fi
    done
}

# ============================================================
# Shared ffmpeg argument tails
#
# Each fills an array with the half of a command line that does not depend on the platform, so a
# caller writes only its own capture input. They are arrays rather than strings because a value
# like SCREEN_SEGMENT_FORMAT_OPTIONS contains characters the shell would otherwise re-split.
# ============================================================

# H.264 into hourly fragmented-MP4 segments. Fragmented, so the segment being written right now is
# still playable after a power cut instead of only becoming valid when ffmpeg closes it.
screen_segment_args() {
    # shellcheck disable=SC2034  # consumed by the platform script that called this
    SCREEN_SEGMENT_ARGS=(
        -c:v libx264 -preset "$SCREEN_PRESET" -crf "$SCREEN_CRF"
        -g "$SCREEN_KEYFRAME_INTERVAL" -keyint_min "$SCREEN_KEYFRAME_INTERVAL" -sc_threshold 0
        -f segment -segment_time "$SEGMENT_DURATION" -reset_timestamps 1
        -segment_format "$SCREEN_SEGMENT_FORMAT"
        -segment_format_options "$SCREEN_SEGMENT_FORMAT_OPTIONS"
        -flush_packets 1 -strftime 1
        "$SCREEN_DIR/screen-%Y-%m-%d-%H.$SCREEN_SEGMENT_EXTENSION"
    )
}

# Uncompressed WAV segments, for the microphone and the system-audio capture alike.
# $1 is the strftime output pattern.
wav_segment_args() {
    # shellcheck disable=SC2034  # consumed by the platform script that called this
    WAV_SEGMENT_ARGS=(
        -acodec pcm_s16le
        -f segment -segment_time "$SEGMENT_DURATION" -strftime 1
        "$1"
    )
}

# The encoder and output half of the optional live stream. The caller prepends its capture input.
remote_stream_args() {
    # shellcheck disable=SC2034,SC2153  # consumed by the caller; values come from config.sh
    REMOTE_STREAM_ARGS=(
        -c:v "$REMOTE_STREAM_CODEC" -preset "$REMOTE_STREAM_PRESET" -crf "$REMOTE_STREAM_CRF"
        -pix_fmt "$REMOTE_STREAM_PIXEL_FORMAT" -tune "$REMOTE_STREAM_TUNE"
        -g "$REMOTE_STREAM_KEYFRAME_INTERVAL" -keyint_min "$REMOTE_STREAM_KEYFRAME_INTERVAL"
        -sc_threshold 0
        -f "$REMOTE_STREAM_FORMAT" "$REMOTE_STREAM_URL"
    )
}

# True only when streaming is both switched on and pointed somewhere. An enabled stream with no URL
# is a misconfiguration that must not stop the local recording, which is the whole point of asking
# this rather than testing REMOTE_STREAM_ENABLED alone.
remote_stream_wanted() {
    [ "$REMOTE_STREAM_ENABLED" = "true" ] && [ -n "$REMOTE_STREAM_URL" ]
}

log_remote_stream_status() {
    if remote_stream_wanted; then
        log_message "INFO" "Remote stream enabled: $REMOTE_STREAM_FORMAT -> $REMOTE_STREAM_URL"
    elif [ "$REMOTE_STREAM_ENABLED" = "true" ]; then
        log_message "WARN" "Remote stream is enabled but REMOTE_STREAM_URL is empty; local recording will continue independently"
    else
        log_message "INFO" "Remote stream disabled"
    fi
}
