#!/bin/bash
set -uo pipefail

# Smoke test for the shared recorder library and the two start_recorder.sh scripts that use it.
#
# Nothing in apps/jarvis/clients is in the Maven reactor, so `mvn test` proves nothing about any of
# it. This runs the real linux recorder against stub binaries and asserts the three things the
# extraction into lib/recorder.sh could plausibly have broken: that each capture is launched with
# the argv it used to get, that a capture which dies is restarted, and that a stop signal shuts
# every capture down instead of orphaning it.
#
# Run it directly:  ./apps/jarvis/clients/lib/recorder-smoke-test.sh

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENTS_DIR="$(cd "$LIB_DIR/.." && pwd)"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

FAILURES=0

pass() { echo "  ok   - $1"; }
fail() { echo "  FAIL - $1"; FAILURES=$((FAILURES + 1)); }

check_contains() {
    local haystack="$1" needle="$2" what="$3"
    case "$haystack" in
        *"$needle"*) pass "$what" ;;
        *) fail "$what"; echo "        wanted to find: $needle" ;;
    esac
}

# ============================================================
# A fake client tree: stub binaries, and a config the recorder can read.
# ============================================================

BIN_DIR="$WORK_DIR/bin"
mkdir -p "$BIN_DIR"

# Records the argv it was called with, then blocks until killed — standing in for a long-running
# capture. ARGV_LOG is where the assertions read from.
cat > "$BIN_DIR/ffmpeg" <<'STUB'
#!/bin/bash
echo "ffmpeg $*" >> "$ARGV_LOG"
if [ -n "${FFMPEG_EXIT_AFTER:-}" ]; then
    sleep "$FFMPEG_EXIT_AFTER"
    exit 1
fi
sleep 300
STUB

cat > "$BIN_DIR/pactl" <<'STUB'
#!/bin/bash
# `pactl list short sources` — one monitor source, as a machine with audio would report.
echo "0	alsa_output.stub.analog-stereo.monitor	module-alsa-card.c	s16le 2ch 48000Hz	IDLE"
STUB

cat > "$BIN_DIR/xdpyinfo" <<'STUB'
#!/bin/bash
echo "  dimensions:    1920x1080 pixels (508x285 millimeters)"
STUB

chmod +x "$BIN_DIR"/*

CLIENT_DIR_UNDER_TEST="$WORK_DIR/client"
mkdir -p "$CLIENT_DIR_UNDER_TEST"
cp "$CLIENTS_DIR/linux/start_recorder.sh" "$CLIENT_DIR_UNDER_TEST/"
# The installed layout: the library sits beside the script as recorder-lib.sh. Testing that layout
# also proves the fallback in the script picks the right one of the two.
cp "$LIB_DIR/recorder.sh" "$CLIENT_DIR_UNDER_TEST/recorder-lib.sh"

cat > "$CLIENT_DIR_UNDER_TEST/config.sh" <<CONFIG
RECORDINGS_DIR="$WORK_DIR/recordings"
SCREEN_DIR="\$RECORDINGS_DIR/screen"
MIC_DIR="\$RECORDINGS_DIR/mic"
SYSTEM_AUDIO_DIR="\$RECORDINGS_DIR/system-audio"
LOGS_DIR="\$RECORDINGS_DIR/logs"
CAPTURE_BACKEND="x11"
REQUIRE_XORG="false"
DISPLAY_NAME=":0.0"
SCREEN_SIZE=""
SCREEN_OFFSET_X="0"
SCREEN_OFFSET_Y="0"
WAYLAND_SCREEN_EXTENSION="webm"
WAYLAND_DRAW_CURSOR="true"
MIC_SOURCE="@DEFAULT_SOURCE@"
SYSTEM_AUDIO_SOURCE="auto"
SCREEN_FPS="15"
SCREEN_CRF="28"
SCREEN_PRESET="ultrafast"
SEGMENT_DURATION="3600"
SCREEN_SEGMENT_EXTENSION="mp4"
SCREEN_SEGMENT_FORMAT="mp4"
SCREEN_SEGMENT_FORMAT_OPTIONS="movflags=+empty_moov+default_base_moof+frag_keyframe"
SCREEN_KEYFRAME_INTERVAL="15"
REMOTE_STREAM_ENABLED="true"
REMOTE_STREAM_URL="rtmp://stub.example.com/live"
REMOTE_STREAM_FORMAT="flv"
REMOTE_STREAM_CODEC="libx264"
REMOTE_STREAM_PRESET="veryfast"
REMOTE_STREAM_CRF="30"
REMOTE_STREAM_PIXEL_FORMAT="yuv420p"
REMOTE_STREAM_TUNE="zerolatency"
REMOTE_STREAM_KEYFRAME_INTERVAL="15"
LOG_LEVEL="INFO"
DEBUG_MODE="false"
DEVICE_HOSTNAME="stub-host"
CONFIG

export ARGV_LOG="$WORK_DIR/argv.log"
: > "$ARGV_LOG"

# ============================================================
# Run the recorder
# ============================================================

echo "recorder-smoke-test: starting the linux recorder against stubs"

PATH="$BIN_DIR:$PATH" \
XDG_SESSION_TYPE="x11" \
XDG_CURRENT_DESKTOP="ubuntu:GNOME" \
    bash "$CLIENT_DIR_UNDER_TEST/start_recorder.sh" > "$WORK_DIR/recorder.out" 2>&1 &
RECORDER_PID=$!

# Wait for all four captures to have registered rather than sleeping a fixed guess.
for _ in $(seq 1 100); do
    [ "$(wc -l < "$ARGV_LOG")" -ge 4 ] && break
    sleep 0.1
done

ARGV="$(cat "$ARGV_LOG")"

echo "argv the recorder produced:"
sed 's/^/    /' "$ARGV_LOG"
echo

echo "each capture is launched with the arguments it had before the extraction:"
check_contains "$ARGV" \
    "-f x11grab -framerate 15 -video_size 1920x1080 -i :0.0+0,0 -c:v libx264 -preset ultrafast -crf 28" \
    "screen capture keeps its x11grab input and H.264 settings"
check_contains "$ARGV" \
    "-segment_format_options movflags=+empty_moov+default_base_moof+frag_keyframe" \
    "screen segments stay fragmented, so a power cut does not lose the open file"
check_contains "$ARGV" \
    "-f segment -segment_time 3600 -reset_timestamps 1" \
    "screen segments stay hourly"
check_contains "$ARGV" \
    "-f pulse -i @DEFAULT_SOURCE@ -acodec pcm_s16le" \
    "microphone capture keeps its pulse source and WAV codec"
check_contains "$ARGV" \
    "-f pulse -i alsa_output.stub.analog-stereo.monitor -acodec pcm_s16le" \
    "system audio follows the auto-detected monitor source"
check_contains "$ARGV" \
    "-tune zerolatency -g 15 -keyint_min 15 -sc_threshold 0 -f flv rtmp://stub.example.com/live" \
    "the live stream keeps its encoder settings and destination"

# ============================================================
# A capture that dies is restarted
# ============================================================

MIC_STARTS_BEFORE="$(grep -c -- "-i @DEFAULT_SOURCE@" "$ARGV_LOG")"
pkill -f "ffmpeg -nostdin -f pulse -i @DEFAULT_SOURCE@" 2>/dev/null || true

# The loop waits 5s before restarting a capture that exited non-zero.
RESTARTED=false
for _ in $(seq 1 120); do
    if [ "$(grep -c -- "-i @DEFAULT_SOURCE@" "$ARGV_LOG")" -gt "$MIC_STARTS_BEFORE" ]; then
        RESTARTED=true
        break
    fi
    sleep 0.25
done
if [ "$RESTARTED" = true ]; then
    pass "a capture that dies is restarted"
else
    fail "a capture that dies is restarted"
fi

# ============================================================
# A stop signal takes every capture down with it
# ============================================================

kill -TERM "$RECORDER_PID" 2>/dev/null || true
wait "$RECORDER_PID" 2>/dev/null

STOPPED=false
for _ in $(seq 1 80); do
    if ! pgrep -f "ffmpeg -nostdin" >/dev/null 2>&1; then
        STOPPED=true
        break
    fi
    sleep 0.25
done
if [ "$STOPPED" = true ]; then
    pass "SIGTERM stops every capture rather than orphaning it"
else
    fail "SIGTERM stops every capture rather than orphaning it"
    pkill -f "ffmpeg -nostdin" 2>/dev/null || true
fi

check_contains "$(cat "$WORK_DIR/recorder.out")" "Recorder stopped" \
    "shutdown is logged, so a stop is distinguishable from a crash"

# ============================================================
# The mac recorder's argv
#
# avfoundation does not exist here, but ffmpeg is a stub, so the script runs far enough to prove
# what it would have asked ffmpeg to do. That is worth checking because -framerate sits *after*
# -i on this platform and before it on linux; the two are different ffmpeg options, and collapsing
# the recorders onto one supervise() is exactly the change that could have quietly unified them.
# ============================================================

echo
echo "recorder-smoke-test: starting the mac recorder against stubs"

MAC_DIR="$WORK_DIR/mac-client"
mkdir -p "$MAC_DIR"
cp "$CLIENTS_DIR/mac/start_recorder.sh" "$MAC_DIR/"
cp "$LIB_DIR/recorder.sh" "$MAC_DIR/recorder-lib.sh"
cp "$CLIENT_DIR_UNDER_TEST/config.sh" "$MAC_DIR/config.sh"
cat >> "$MAC_DIR/config.sh" <<'CONFIG'
SCREEN_DEVICE="Capture screen 0"
MIC_DEVICE="MacBook Microphone"
SYSTEM_AUDIO_DEVICE="BlackHole 2ch"
CONFIG

MAC_ARGV_LOG="$WORK_DIR/argv-mac.log"
: > "$MAC_ARGV_LOG"

ARGV_LOG="$MAC_ARGV_LOG" PATH="$BIN_DIR:$PATH" \
    bash "$MAC_DIR/start_recorder.sh" > "$WORK_DIR/recorder-mac.out" 2>&1 &
MAC_PID=$!

for _ in $(seq 1 100); do
    [ "$(wc -l < "$MAC_ARGV_LOG")" -ge 4 ] && break
    sleep 0.1
done

MAC_ARGV="$(cat "$MAC_ARGV_LOG")"
echo "argv the mac recorder produced:"
sed 's/^/    /' "$MAC_ARGV_LOG"
echo

check_contains "$MAC_ARGV" \
    "-f avfoundation -i Capture screen 0:none -framerate 15 -c:v libx264" \
    "mac screen capture keeps -framerate AFTER -i, as an output rate"
check_contains "$MAC_ARGV" \
    "-f avfoundation -i :MacBook Microphone -acodec pcm_s16le" \
    "mac microphone capture keeps its avfoundation input"
check_contains "$MAC_ARGV" \
    "-f avfoundation -i :BlackHole 2ch -acodec pcm_s16le" \
    "mac system audio still reads BlackHole"
check_contains "$MAC_ARGV" \
    "-i Capture screen 0:none -framerate 15 -c:v libx264 -preset veryfast -crf 30" \
    "mac live stream keeps its own encoder settings"

kill -TERM "$MAC_PID" 2>/dev/null || true
wait "$MAC_PID" 2>/dev/null
for _ in $(seq 1 80); do
    pgrep -f "ffmpeg -nostdin" >/dev/null 2>&1 || break
    sleep 0.25
done
pkill -f "ffmpeg -nostdin" 2>/dev/null || true

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "recorder-smoke-test: all checks passed"
    exit 0
fi
echo "recorder-smoke-test: $FAILURES check(s) failed"
exit 1
