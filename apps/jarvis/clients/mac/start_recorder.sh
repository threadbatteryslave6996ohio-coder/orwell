#!/bin/bash
set -uo pipefail

# Jarvis macOS recorder.
# Captures screen, microphone and system audio through avfoundation, into separate files.
# Runs continuously in the background via the LaunchAgent that setup-launchagent.sh installs.
#
# Everything not specific to avfoundation — logging, the shutdown trap, process supervision, the
# segment and streaming argument tails — lives in the shared library below.

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

require_command ffmpeg "Install it with: brew install ffmpeg"

# A device name that no longer matches what avfoundation reports is the usual reason a fresh
# install records nothing, and ffmpeg's own error for it is not obvious. Warn rather than exit —
# a missing microphone must not stop the screen being recorded — and query the list once rather
# than once per device, because enumerating capture devices is slow.
check_devices() {
    local devices
    devices="$(ffmpeg -f avfoundation -list_devices true -i "" 2>&1)"
    warn_unless_listed "$devices" "Screen" "$SCREEN_DEVICE"
    warn_unless_listed "$devices" "Microphone" "$MIC_DEVICE"
    warn_unless_listed "$devices" "System audio" "$SYSTEM_AUDIO_DEVICE"
}

warn_unless_listed() {
    local devices="$1"
    local label="$2"
    local device="$3"
    if ! echo "$devices" | grep -q "$device"; then
        log_message "WARN" "$label device '$device' not found in the avfoundation device list"
    fi
}

log_message "INFO" "=== Jarvis recorder starting ==="
log_message "INFO" "Screen device: $SCREEN_DEVICE"
log_message "INFO" "Microphone device: $MIC_DEVICE"
log_message "INFO" "System audio device: $SYSTEM_AUDIO_DEVICE"
log_message "INFO" "Recording directory: $RECORDINGS_DIR"
log_remote_stream_status

if [ "$DEBUG_MODE" = "true" ]; then
    log_message "DEBUG" "Checking devices..."
    check_devices
fi

# NOTE: -framerate goes *after* -i here, so it is an output frame rate rather than an avfoundation
# capture rate. That is what this recorder has always done; the linux client passes it before -i
# because x11grab needs it as an input option. Do not "tidy" the two into agreement without
# deciding which behaviour is wanted — moving it changes what is captured, not just where a flag
# sits.
log_message "INFO" "Starting screen recording (MP4, H.264, $SCREEN_FPS fps, CRF $SCREEN_CRF)..."
screen_segment_args
supervise "screen" \
    ffmpeg -nostdin -f avfoundation -i "$SCREEN_DEVICE:none" \
    -framerate "$SCREEN_FPS" "${SCREEN_SEGMENT_ARGS[@]}"

if remote_stream_wanted; then
    log_message "INFO" "Starting remote screen stream ($REMOTE_STREAM_FORMAT)..."
    remote_stream_args
    supervise "screen-stream" \
        ffmpeg -nostdin -f avfoundation -i "$SCREEN_DEVICE:none" \
        -framerate "$SCREEN_FPS" "${REMOTE_STREAM_ARGS[@]}"
fi

log_message "INFO" "Starting microphone recording (WAV)..."
wav_segment_args "$MIC_DIR/mic-%Y-%m-%d-%H.wav"
supervise "microphone" \
    ffmpeg -nostdin -f avfoundation -i ":$MIC_DEVICE" \
    "${WAV_SEGMENT_ARGS[@]}"

# System audio has no capture device of its own on macOS; BlackHole is a virtual output the user
# routes sound through, which then appears here as an ordinary input.
log_message "INFO" "Starting system audio recording (WAV via BlackHole)..."
wav_segment_args "$SYSTEM_AUDIO_DIR/system-%Y-%m-%d-%H.wav"
supervise "system-audio" \
    ffmpeg -nostdin -f avfoundation -i ":$SYSTEM_AUDIO_DEVICE" \
    "${WAV_SEGMENT_ARGS[@]}"

log_message "INFO" "All recording processes started. Monitoring..."

# Stay alive so the signal traps can run: this process is what the LaunchAgent supervises, and it
# is what turns a stop request into a clean shutdown of every capture.
wait
