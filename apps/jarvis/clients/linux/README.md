# Keeboarder Ubuntu GNOME Client - FFmpeg + AWS S3

Shell-based Linux client for Ubuntu GNOME on Wayland or Xorg. It records screen, microphone, and system audio, starts automatically in the user's graphical session with systemd user services, and uploads recordings through the authenticated S3 proxy by default.

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

`awscli` and `aws configure` are only needed if you set `UPLOAD_MODE="s3"` for direct S3 upload.

Verify audio sources:

```bash
pactl list short sources
```

System audio is captured from a `.monitor` source. If auto-detection picks the wrong one, set `SYSTEM_AUDIO_SOURCE` in `config.sh`.

## Installation

```bash
cd clients/linux
chmod +x *.sh
./install.sh
```

Edit `config.sh` first if you need a different S3 bucket, capture backend, display, screen size, or audio source.

## How It Works

- `start_recorder.sh` starts supervised capture loops:
  - GNOME Wayland screen capture to `~/recordings/screen/*.webm`, or X11 capture to `*.mp4`
  - microphone audio to `~/recordings/mic/*.wav`
  - system audio monitor source to `~/recordings/system-audio/*.wav`
- Live streaming is enabled by default and starts once `REMOTE_STREAM_URL` is set. It runs in a separate supervised FFmpeg loop, so stream failures do not stop local recording and local-recorder failures do not stop the stream.
- `setup-systemd.sh` creates a user systemd recorder service plus upload/verification timers.
- `upload_to_s3.sh` uploads completed files through the proxy with username/password login and Bearer-token retry on `401`. Direct S3 upload remains available with `UPLOAD_MODE="s3"`.
- Screen video is written as fragmented MP4, so a sudden power loss is much less likely to corrupt the active segment.
- Local recording and remote streaming run as independent FFmpeg jobs, so a failure in one does not stop the other.
- Uploads run `ffprobe` before uploading/deleting media, so broken or partially written files stay local for inspection.
- Completed videos are checked against the server before upload. A matching remote size is skipped; a missing or different object is uploaded. Local files are retained by default.
- `sync_videos.sh` (or `./manage.sh sync`) runs this check-and-upload flow for videos only.
- Sync/upload runs use a single-process lock. Files still open by FFmpeg are skipped, and size/mtime are checked again immediately before transfer. If open-file inspection is unavailable, the client requires an unchanged stability window before proceeding.
- `verify_uploads.sh` checks local files against S3.
- `manage.sh` starts/stops services, checks status, tails logs, uploads, verifies, and cleans old files.

## File Layout

```text
~/recordings/
├── screen/
├── mic/
├── system-audio/
└── logs/
    ├── recorder.log
    ├── uploader.log
    └── verification.log
```

S3 layout:

```text
s3://BUCKET/recordings/HOSTNAME/screen/
s3://BUCKET/recordings/HOSTNAME/microphone/
s3://BUCKET/recordings/HOSTNAME/system-audio/
```

## Common Commands

```bash
./manage.sh status
./manage.sh logs
./manage.sh logs journal
./manage.sh restart
./manage.sh upload
./manage.sh verify
./manage.sh cleanup 30
```

Direct systemd commands:

```bash
systemctl --user status keeboarder-recorder.service
systemctl --user list-timers 'keeboarder-*'
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

## Proxy Authentication Mode

Set these in `config.sh` to upload through `bucket/proxy`:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="https://your-proxy-host"
PROXY_USERNAME="uploader"
PROXY_PASSWORD="change-me"
```

`PROXY_USERNAME` and `PROXY_PASSWORD` must be an auth-server client identity issued from the proxy's `/admin` management panel.

The uploader logs every auth attempt, auth response, upload send, upload response, unauthorized retry, and token save to `~/recordings/logs/uploader.log`. Tokens are cached in `~/recordings/logs/.proxy_token` with `0600` permissions.

Verification uses the same cached token and refresh-on-`401` behavior for `/metadata` requests. It logs each metadata send and response to `~/recordings/logs/verification.log`.

## Optional Remote Stream

To mirror the screen capture to a live endpoint, set these in `config.sh`:

```bash
REMOTE_STREAM_ENABLED="true"
REMOTE_STREAM_URL="rtmp://stream.example.com/live"
REMOTE_STREAM_FORMAT="flv"
```

`REMOTE_STREAM_ENABLED` defaults to `true`; an empty `REMOTE_STREAM_URL` logs a warning while local recording continues. Live screen streaming currently requires the X11 backend; GNOME Wayland still records locally. Set `DELETE_AFTER_UPLOAD="true"` only if synchronized local copies should be removed.

Use `flv` for RTMP/RTMPS and `mpegts` for SRT/UDP-style ingest endpoints. The stream job is isolated from local recording, so if the endpoint goes down the saved files keep writing.
