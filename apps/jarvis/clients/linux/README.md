# Jarvis Ubuntu GNOME Client - FFmpeg

Shell-based Linux **recording** client for Ubuntu GNOME on Wayland or Xorg. It records screen, microphone, and system audio to local files and starts automatically in the user's graphical session with a systemd user service.

This client is **record-only**. It does not upload. Completed recording segments are drained to the bucket by the separate **syncer** client at `apps/jarvis/clients/syncer/`, run on a timer (see [Uploads](#uploads)).

## GNOME Capture Backends

`CAPTURE_BACKEND="auto"` supports both GNOME session types:

- GNOME Wayland uses GNOME Shell's PipeWire-backed screencast service and writes WebM segments.
- Xorg uses FFmpeg `x11grab` and writes fragmented MP4 segments.

The Wayland recorder must run as a systemd user service inside the logged-in graphical session. It does not bypass GNOME's session boundary and cannot record from a system service or before login.

## Prerequisites

```bash
sudo apt update
sudo apt install ffmpeg curl pulseaudio-utils x11-utils gjs
sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-good
```

Verify audio sources:

```bash
pactl list short sources
```

System audio is captured from a `.monitor` source. If auto-detection picks the wrong one, set `SYSTEM_AUDIO_SOURCE` in `config.sh`.

## Installation

```bash
cd apps/jarvis/clients/linux
chmod +x *.sh
./install.sh
```

Edit `config.sh` first if you need a different capture backend, display, screen size, or audio source.

## How It Works

- `start_recorder.sh` starts supervised capture loops:
  - GNOME Wayland screen capture to `~/recordings/screen/*.webm`, or X11 capture to `*.mp4`
  - microphone audio to `~/recordings/mic/*.wav`
  - system audio monitor source to `~/recordings/system-audio/*.wav`
- Live streaming is enabled by default and starts once `REMOTE_STREAM_URL` is set. It runs in a separate supervised FFmpeg loop, so stream failures do not stop local recording and local-recorder failures do not stop the stream.
- `setup-systemd.sh` creates a user systemd recorder service.
- Screen video is written as fragmented MP4, so a sudden power loss is much less likely to corrupt the active segment.
- Local recording and remote streaming run as independent FFmpeg jobs, so a failure in one does not stop the other.
- `manage.sh` starts/stops the recorder service, checks status, tails logs, and cleans old local files.

## Uploads

Uploading recordings is **not** this client's job. Point the **syncer** client at
`apps/jarvis/clients/syncer/` at the recordings directory (`~/recordings`) and run it on a timer;
it logs in to the bucket proxy and drains completed segments through the proxy's `/upload` flow.
See that client's README for configuration and the recommended timer interval.

## File Layout

```text
~/recordings/
├── screen/
├── mic/
├── system-audio/
└── logs/
    └── recorder.log
```

## Common Commands

```bash
./manage.sh status
./manage.sh logs
./manage.sh logs journal
./manage.sh restart
./manage.sh cleanup 30
```

Direct systemd commands:

```bash
systemctl --user status keeboarder-recorder.service
journalctl --user -u keeboarder-recorder.service -f
```

## Troubleshooting

If screen recording fails, check:

```bash
echo "$XDG_SESSION_TYPE"
echo "$XDG_CURRENT_DESKTOP"
echo "$DBUS_SESSION_BUS_ADDRESS"
gdbus introspect --session --dest org.gnome.Shell.Screencast --object-path /org/gnome/Shell/Screencast
```

For X11, also verify `DISPLAY` and `xdpyinfo`. For Wayland, verify that `gjs`, `pipewiresrc`, `vp8enc`, and `webmmux` are installed and that the service runs under the logged-in GNOME user.

If audio fails, inspect sources:

```bash
pactl list short sources
```

Use `@DEFAULT_SOURCE@` for the microphone and a source ending in `.monitor` for system audio.

## Optional Remote Stream

To mirror the screen capture to a live endpoint, set these in `config.sh`:

```bash
REMOTE_STREAM_ENABLED="true"
REMOTE_STREAM_URL="rtmp://stream.example.com/live"
REMOTE_STREAM_FORMAT="flv"
```

`REMOTE_STREAM_ENABLED` defaults to `true`; an empty `REMOTE_STREAM_URL` logs a warning while local recording continues. Live screen streaming currently requires the X11 backend; GNOME Wayland still records locally.

Use `flv` for RTMP/RTMPS and `mpegts` for SRT/UDP-style ingest endpoints. The stream job is isolated from local recording, so if the endpoint goes down the saved files keep writing.
