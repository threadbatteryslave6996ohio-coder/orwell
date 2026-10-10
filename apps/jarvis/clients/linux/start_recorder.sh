#!/bin/bash
set -uo pipefail

# Jarvis Ubuntu recorder.
# Captures the X11 or GNOME Wayland screen, microphone and system audio into separate files.
#
# Everything not specific to this platform — logging, the shutdown trap, process supervision, the
# segment and streaming argument tails — lives in the shared library below. What stays here is the
# part that has no macOS equivalent: choosing between x11grab and GNOME Shell's PipeWire
# screencast service, and finding a PulseAudio monitor source for system audio.

CLIENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# install.sh flattens the client into ~/scripts, so the library sits beside this script once
# installed and one directory up in a repo checkout.
if [ -r "$CLIENT_DIR/recorder-lib.sh" ]; then
    # shellcheck source=/dev/null
    source "$CLIENT_DIR/recorder-lib.sh"
else
    # shellcheck source=../lib/recorder.sh
    source "$CLIENT_DIR/../lib/recorder.sh"
fi

: "${CAPTURE_BACKEND:=auto}"
: "${REQUIRE_XORG:=false}"
: "${WAYLAND_SCREEN_EXTENSION:=webm}"
: "${WAYLAND_DRAW_CURSOR:=true}"

require_command ffmpeg "Install it with: sudo apt install ffmpeg"
require_command pactl "Install it with: sudo apt install pulseaudio-utils"

# ============================================================
# Session detection
# ============================================================

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
elif [ ! -r "$CLIENT_DIR/gnome_wayland_recorder.js" ]; then
    log_message "ERROR" "GNOME Wayland helper is missing: $CLIENT_DIR/gnome_wayland_recorder.js"
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

log_message "INFO" "=== Jarvis recorder starting ==="
log_message "INFO" "Capture backend: $ACTIVE_CAPTURE_BACKEND"
if [ "$ACTIVE_CAPTURE_BACKEND" = "x11" ]; then
    log_message "INFO" "Display: $DISPLAY"
    log_message "INFO" "Screen geometry: $SCREEN_GEOMETRY+$SCREEN_OFFSET_X,$SCREEN_OFFSET_Y"
fi
log_message "INFO" "Microphone source: $MIC_SOURCE"
log_message "INFO" "System audio source: ${MONITOR_SOURCE:-unavailable}"
log_message "INFO" "Recording directory: $RECORDINGS_DIR"
log_remote_stream_status

# ============================================================
# Captures
# ============================================================

# GNOME Shell's screencast service records one file per call rather than a continuous stream, so
# each attempt needs its own output name — which is why this goes through supervise_building
# rather than supervise. The restart loop is what turns single-segment calls into a continuous
# recording.
build_wayland_screen_command() {
    local stamp
    stamp=$(date '+%Y-%m-%d-%H-%M-%S')
    local output_file="$SCREEN_DIR/screen-$stamp.$WAYLAND_SCREEN_EXTENSION"
    log_message "INFO" "Next GNOME Wayland screen segment: $output_file"
    # shellcheck disable=SC2034  # read by supervise_building in the shared library
    RECORDER_COMMAND=(
        gjs "$CLIENT_DIR/gnome_wayland_recorder.js"
        "$output_file" "$SEGMENT_DURATION" "$SCREEN_FPS" "$WAYLAND_DRAW_CURSOR"
    )
}

if [ "$ACTIVE_CAPTURE_BACKEND" = "gnome-wayland" ]; then
    log_message "INFO" "Starting GNOME Wayland screen recording (WebM, $SCREEN_FPS fps)..."
    supervise_building "wayland-screen" build_wayland_screen_command
    if remote_stream_wanted; then
        log_message "WARN" "Live screen streaming is unavailable with the GNOME Wayland backend; local recording remains enabled."
    fi
else
    log_message "INFO" "Starting X11 screen recording ($SCREEN_SEGMENT_FORMAT, H.264, $SCREEN_FPS fps, CRF $SCREEN_CRF)..."
    screen_segment_args
    supervise "screen" \
        ffmpeg -nostdin -f x11grab -framerate "$SCREEN_FPS" -video_size "$SCREEN_GEOMETRY" \
        -i "$DISPLAY+$SCREEN_OFFSET_X,$SCREEN_OFFSET_Y" \
        "${SCREEN_SEGMENT_ARGS[@]}"

    if remote_stream_wanted; then
        log_message "INFO" "Starting remote screen stream ($REMOTE_STREAM_FORMAT)..."
        remote_stream_args
        supervise "screen-stream" \
            ffmpeg -nostdin -f x11grab -framerate "$SCREEN_FPS" -video_size "$SCREEN_GEOMETRY" \
            -i "$DISPLAY+$SCREEN_OFFSET_X,$SCREEN_OFFSET_Y" \
            "${REMOTE_STREAM_ARGS[@]}"
    fi
fi

log_message "INFO" "Starting microphone recording (WAV)..."
wav_segment_args "$MIC_DIR/mic-%Y-%m-%d-%H.wav"
supervise "microphone" \
    ffmpeg -nostdin -f pulse -i "$MIC_SOURCE" \
    "${WAV_SEGMENT_ARGS[@]}"

if [ -n "$MONITOR_SOURCE" ]; then
    log_message "INFO" "Starting system audio recording (WAV)..."
    wav_segment_args "$SYSTEM_AUDIO_DIR/system-%Y-%m-%d-%H.wav"
    supervise "system-audio" \
        ffmpeg -nostdin -f pulse -i "$MONITOR_SOURCE" \
        "${WAV_SEGMENT_ARGS[@]}"
fi

log_message "INFO" "Recording processes started. Monitoring..."

# Stay alive so the signal traps can run: this process is what systemd supervises, and it is what
# turns a stop request into a clean shutdown of every capture.
wait
