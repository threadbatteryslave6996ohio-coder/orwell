#!/bin/bash
set -uo pipefail

# Keeboarder Ubuntu GNOME Recorder
# Records X11 or GNOME Wayland screen, microphone, and system audio separately.

CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$CONFIG_DIR/config.sh"
: "${CAPTURE_BACKEND:=auto}"
: "${REQUIRE_XORG:=false}"
: "${WAYLAND_SCREEN_EXTENSION:=webm}"
: "${WAYLAND_DRAW_CURSOR:=true}"

RECORDER_LOG="$LOGS_DIR/recorder.log"
mkdir -p "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR" "$LOGS_DIR"
PROCESS_PIDS=()

log_message() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$RECORDER_LOG"
}

cleanup() {
    log_message "INFO" "Shutting down recorder gracefully..."
    for pid in "${PROCESS_PIDS[@]:-}"; do
        kill "$pid" 2>/dev/null || true
    done
    wait 2>/dev/null || true
    log_message "INFO" "Recorder stopped"
    exit 0
}

trap cleanup SIGTERM SIGINT SIGHUP

if ! command -v ffmpeg >/dev/null 2>&1; then
    log_message "ERROR" "FFmpeg not found. Install with: sudo apt install ffmpeg"
    exit 1
fi

if ! command -v pactl >/dev/null 2>&1; then
    log_message "ERROR" "pactl not found. Install with: sudo apt install pulseaudio-utils"
    exit 1
fi

detect_capture_backend() {
    local requested="${CAPTURE_BACKEND:-auto}"
    local session_type="${XDG_SESSION_TYPE:-}"
    local desktop="${XDG_CURRENT_DESKTOP:-}"

    case "$requested" in
        x11|gnome-wayland)
            echo "$requested"
            ;;
        auto)
            if [ "$session_type" = "wayland" ]; then
                case "$desktop" in
                    *GNOME*|*Gnome*|*gnome*|*ubuntu*|*Ubuntu*) echo "gnome-wayland" ;;
                    *) return 1 ;;
                esac
            else
                echo "x11"
            fi
            ;;
        *) return 1 ;;
    esac
}

detect_screen_size() {
    if [ -n "$SCREEN_SIZE" ]; then
        echo "$SCREEN_SIZE"
        return
    fi

    if command -v xdpyinfo >/dev/null 2>&1; then
        xdpyinfo 2>/dev/null | awk '/dimensions:/ {print $2; exit}'
        return
    fi

    echo "1920x1080"
}

detect_monitor_source() {
    if [ "$SYSTEM_AUDIO_SOURCE" != "auto" ]; then
        echo "$SYSTEM_AUDIO_SOURCE"
        return
    fi

    pactl list short sources | awk '$2 ~ /\.monitor$/ {print $2; exit}'
}

if ! ACTIVE_CAPTURE_BACKEND="$(detect_capture_backend)"; then
    log_message "ERROR" "Cannot select capture backend for session '${XDG_SESSION_TYPE:-unknown}' and desktop '${XDG_CURRENT_DESKTOP:-unknown}'."
    log_message "ERROR" "Set CAPTURE_BACKEND to x11 or gnome-wayland in config.sh."
    exit 1
fi

if [ "$REQUIRE_XORG" = "true" ] && [ "$ACTIVE_CAPTURE_BACKEND" = "gnome-wayland" ]; then
    log_message "ERROR" "Wayland was detected but REQUIRE_XORG=true. Set REQUIRE_XORG=false to enable GNOME Wayland capture."
    exit 1
fi

if [ "$ACTIVE_CAPTURE_BACKEND" = "x11" ] && [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; then
    log_message "ERROR" "The x11 backend cannot capture the full GNOME Wayland desktop. Use CAPTURE_BACKEND=auto or gnome-wayland."
    exit 1
fi

if [ "$ACTIVE_CAPTURE_BACKEND" = "gnome-wayland" ]; then
    case "${XDG_CURRENT_DESKTOP:-}" in
        *GNOME*|*Gnome*|*gnome*|*ubuntu*|*Ubuntu*) ;;
        *)
            log_message "ERROR" "The gnome-wayland backend requires a GNOME desktop session."
            exit 1
            ;;
    esac
fi

SCREEN_GEOMETRY=""
if [ "$ACTIVE_CAPTURE_BACKEND" = "x11" ]; then
    export DISPLAY="$DISPLAY_NAME"
    if [ -z "${XAUTHORITY:-}" ] && [ -f "$HOME/.Xauthority" ]; then
        export XAUTHORITY="$HOME/.Xauthority"
    fi
    SCREEN_GEOMETRY="$(detect_screen_size)"
elif ! command -v gjs >/dev/null 2>&1; then
    log_message "ERROR" "gjs is required for GNOME Wayland capture. Install it with: sudo apt install gjs"
    exit 1
elif [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
    log_message "ERROR" "DBUS_SESSION_BUS_ADDRESS is unavailable. Start the recorder from the logged-in GNOME user session."
    exit 1
elif [ ! -r "$CONFIG_DIR/gnome_wayland_recorder.js" ]; then
    log_message "ERROR" "GNOME Wayland helper is missing: $CONFIG_DIR/gnome_wayland_recorder.js"
    exit 1
fi

MONITOR_SOURCE="$(detect_monitor_source)"

if [ "$ACTIVE_CAPTURE_BACKEND" = "x11" ] && [ -z "$SCREEN_GEOMETRY" ]; then
    log_message "ERROR" "Could not detect screen size. Set SCREEN_SIZE in config.sh."
    exit 1
fi

if [ -z "$MONITOR_SOURCE" ]; then
    log_message "WARN" "No monitor source found for system audio. Set SYSTEM_AUDIO_SOURCE in config.sh."
fi

log_message "INFO" "=== Keeboarder Recorder Starting ==="
log_message "INFO" "Capture backend: $ACTIVE_CAPTURE_BACKEND"
if [ "$ACTIVE_CAPTURE_BACKEND" = "x11" ]; then
    log_message "INFO" "Display: $DISPLAY"
    log_message "INFO" "Screen geometry: $SCREEN_GEOMETRY+$SCREEN_OFFSET_X,$SCREEN_OFFSET_Y"
fi
log_message "INFO" "Microphone source: $MIC_SOURCE"
log_message "INFO" "System audio source: ${MONITOR_SOURCE:-unavailable}"
log_message "INFO" "Recording directory: $RECORDINGS_DIR"
if [ "$REMOTE_STREAM_ENABLED" = "true" ] && [ -n "$REMOTE_STREAM_URL" ]; then
    log_message "INFO" "Remote stream enabled: $REMOTE_STREAM_FORMAT -> $REMOTE_STREAM_URL"
elif [ "$REMOTE_STREAM_ENABLED" = "true" ]; then
    log_message "WARN" "Remote stream is enabled but REMOTE_STREAM_URL is empty; local recording will continue independently"
else
    log_message "INFO" "Remote stream disabled"
fi

start_ffmpeg_process() {
    local name="$1"
    shift

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
            ffmpeg -nostdin "$@" >> "$RECORDER_LOG" 2>&1 &
            local pid=$!
            current_pid="$pid"
            log_message "DEBUG" "$name process started with PID $pid"
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

start_wayland_screen_process() {
    (
        current_pid=""
        cleanup_child() {
            if [ -n "$current_pid" ]; then
                kill "$current_pid" 2>/dev/null || true
                wait "$current_pid" 2>/dev/null || true
            fi
        }
        trap cleanup_child EXIT
        trap 'cleanup_child; exit 0' SIGTERM SIGINT SIGHUP

        while true; do
            local output_file="$SCREEN_DIR/screen-$(date '+%Y-%m-%d-%H-%M-%S').$WAYLAND_SCREEN_EXTENSION"
            log_message "INFO" "Starting GNOME Wayland screen segment: $output_file"
            gjs "$CONFIG_DIR/gnome_wayland_recorder.js" \
                "$output_file" "$SEGMENT_DURATION" "$SCREEN_FPS" "$WAYLAND_DRAW_CURSOR" \
                >> "$RECORDER_LOG" 2>&1 &
            current_pid=$!
            wait "$current_pid"
            local exit_code=$?
            current_pid=""

            if [ "$exit_code" -ne 0 ]; then
                log_message "WARN" "GNOME Wayland recorder exited with code $exit_code. Restarting in 5 seconds..."
                sleep 5
            fi
        done
    ) &
    PROCESS_PIDS+=("$!")
}

if [ "$ACTIVE_CAPTURE_BACKEND" = "gnome-wayland" ]; then
    log_message "INFO" "Starting GNOME Wayland screen recording (WebM, $SCREEN_FPS fps)..."
    start_wayland_screen_process
    if [ "$REMOTE_STREAM_ENABLED" = "true" ] && [ -n "$REMOTE_STREAM_URL" ]; then
        log_message "WARN" "Live screen streaming is unavailable with the GNOME Wayland backend; local recording remains enabled."
    fi
else
    log_message "INFO" "Starting X11 screen recording ($SCREEN_SEGMENT_FORMAT, H.264, $SCREEN_FPS fps, CRF $SCREEN_CRF)..."
    start_ffmpeg_process "screen" \
        -f x11grab -framerate "$SCREEN_FPS" -video_size "$SCREEN_GEOMETRY" \
        -i "$DISPLAY+$SCREEN_OFFSET_X,$SCREEN_OFFSET_Y" \
        -c:v libx264 -preset "$SCREEN_PRESET" -crf "$SCREEN_CRF" \
        -g "$SCREEN_KEYFRAME_INTERVAL" -keyint_min "$SCREEN_KEYFRAME_INTERVAL" -sc_threshold 0 \
        -f segment -segment_time "$SEGMENT_DURATION" -reset_timestamps 1 \
        -segment_format "$SCREEN_SEGMENT_FORMAT" \
        -segment_format_options "$SCREEN_SEGMENT_FORMAT_OPTIONS" \
        -flush_packets 1 -strftime 1 \
        "$SCREEN_DIR/screen-%Y-%m-%d-%H.$SCREEN_SEGMENT_EXTENSION"

    if [ "$REMOTE_STREAM_ENABLED" = "true" ] && [ -n "$REMOTE_STREAM_URL" ]; then
        log_message "INFO" "Starting remote screen stream ($REMOTE_STREAM_FORMAT)..."
        start_ffmpeg_process "screen-stream" \
            -f x11grab -framerate "$SCREEN_FPS" -video_size "$SCREEN_GEOMETRY" \
            -i "$DISPLAY+$SCREEN_OFFSET_X,$SCREEN_OFFSET_Y" \
            -c:v "$REMOTE_STREAM_CODEC" -preset "$REMOTE_STREAM_PRESET" -crf "$REMOTE_STREAM_CRF" \
            -pix_fmt "$REMOTE_STREAM_PIXEL_FORMAT" -tune "$REMOTE_STREAM_TUNE" \
            -g "$REMOTE_STREAM_KEYFRAME_INTERVAL" -keyint_min "$REMOTE_STREAM_KEYFRAME_INTERVAL" -sc_threshold 0 \
            -f "$REMOTE_STREAM_FORMAT" "$REMOTE_STREAM_URL"
    fi
fi

log_message "INFO" "Starting microphone recording (WAV)..."
start_ffmpeg_process "microphone" \
    -f pulse -i "$MIC_SOURCE" \
    -acodec pcm_s16le \
    -f segment -segment_time "$SEGMENT_DURATION" -strftime 1 \
    "$MIC_DIR/mic-%Y-%m-%d-%H.wav"

if [ -n "$MONITOR_SOURCE" ]; then
    log_message "INFO" "Starting system audio recording (WAV)..."
    start_ffmpeg_process "system-audio" \
        -f pulse -i "$MONITOR_SOURCE" \
        -acodec pcm_s16le \
        -f segment -segment_time "$SEGMENT_DURATION" -strftime 1 \
        "$SYSTEM_AUDIO_DIR/system-%Y-%m-%d-%H.wav"
fi

log_message "INFO" "Recording processes started. Monitoring..."
wait
