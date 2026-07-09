# Jarvis macOS Client - FFmpeg

macOS recording client that captures screen, microphone, and system audio using FFmpeg, starts on login via LaunchAgent, and uploads recordings through the Bucket Proxy.

## Prerequisites

```bash
brew install ffmpeg
brew install --cask blackhole-2ch
```

Find device names:
```bash
ffmpeg -f avfoundation -list_devices true -i ""
```

## Installation

```bash
cd apps/jarvis/clients/mac
chmod +x install.sh
./install.sh
```

Edit `config.sh` to set bucket proxy URL, credentials, recording directories, and device names.

## Run

```bash
./setup-launchagent.sh load
```

Grant permissions in **System Settings → Privacy & Security**: Screen Recording, Microphone, and Full Disk Access for Terminal.

## File Structure

```
~/recordings/
├── screen/
├── mic/
├── system-audio/
└── logs/
    ├── recorder.log
    ├── uploader.log
    └── verification.log
```

## Commands

| Action | Command |
|---|---|
| Start | `./setup-launchagent.sh load` |
| Stop | `./setup-launchagent.sh unload` |
| Status | `./setup-launchagent.sh status` |
| View logs | `tail -f ~/recordings/logs/recorder.log` |

## Proxy Authentication Mode

Set these in `config.sh`:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="https://your-proxy-host"
PROXY_USERNAME="uploader"
PROXY_PASSWORD="change-me"
```

`PROXY_USERNAME` and `PROXY_PASSWORD` must be an auth-server client identity issued from the proxy's `/admin` management panel.

## Optional Remote Stream

To mirror screen capture to a live endpoint, set in `config.sh`:

```bash
REMOTE_STREAM_ENABLED="true"
REMOTE_STREAM_URL="rtmp://stream.example.com/live"
REMOTE_STREAM_FORMAT="flv"
```

Use `flv` for RTMP/RTMPS and `mpegts` for SRT/UDP-style ingest. The stream job is isolated from local recording.

## Troubleshooting

- Verify permissions: System Settings → Privacy & Security → Screen Recording
- Check device names: `ffmpeg -f avfoundation -list_devices true -i ""`
- Test FFmpeg: `ffmpeg -f avfoundation -i "Capture screen 0:none" -t 5 test.mp4`
- Check LaunchAgent: `./setup-launchagent.sh status`

## Performance Notes

- Screen recording at 15 FPS, CRF 28
- Files segment hourly
- Upload checks file age (5s) to avoid uploading incomplete files
- Local recording and remote streaming are separate FFmpeg jobs
