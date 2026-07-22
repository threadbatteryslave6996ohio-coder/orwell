# Jarvis macOS Client - FFmpeg

macOS **record-only** client that captures screen, microphone, and system audio using FFmpeg and
starts on login via a LaunchAgent. It writes recordings to local disk only — uploading them is the
job of the separate **syncer** client (see [Uploading recordings](#uploading-recordings)).

## Prerequisites

- macOS 10.13+, Homebrew

```bash
brew install ffmpeg
brew install --cask blackhole-2ch
```

Find device names:
```bash
ffmpeg -f avfoundation -list_devices true -i ""
```

Look for the screen device (e.g. "Capture screen 0"), the microphone (e.g. "MacBook
Microphone"), and BlackHole (e.g. "BlackHole 2ch").

## Installation

```bash
cd apps/jarvis/clients/mac
chmod +x install.sh
./install.sh
```

The installer verifies the tools are present, checks the capture devices, creates the recording
directories, installs the scripts, and sets up the LaunchAgent.

Edit `config.sh` to set the recording directories and device names:

```bash
SCREEN_DEVICE="Capture screen 0"
MIC_DEVICE="MacBook Microphone"
SYSTEM_AUDIO_DEVICE="BlackHole 2ch"
```

## Run

```bash
./setup-launchagent.sh load
```

Grant permissions in **System Settings → Privacy & Security**: Screen Recording, Microphone, and Full Disk Access for Terminal. Without Screen Recording and Microphone, recording will not work at all.

## Uploading recordings

This client only records to local disk. Uploads are handled entirely by the separate **syncer**
client at `apps/jarvis/clients/syncer/`, which drains completed recording segments to the bucket
proxy via a `/login` + `/upload` flow, leaving the segment the recorder is still writing untouched.

Run the syncer on a timer (cron or a systemd/launchd timer) alongside the recorder. See
`apps/jarvis/clients/syncer/README.md` for its configuration and usage.

## File Structure

```
~/recordings/
├── screen/
├── mic/
├── system-audio/
└── logs/
    └── recorder.log
```

## Commands

| Action | Command |
|---|---|
| Start | `./setup-launchagent.sh load` |
| Stop | `./setup-launchagent.sh unload` |
| Status | `./setup-launchagent.sh status` |
| View logs | `tail -f ~/recordings/logs/recorder.log` |

`manage.sh` wraps the recorder lifecycle:

```bash
./manage.sh status         # current state and disk usage
./manage.sh logs           # recent log output
./manage.sh cleanup 30     # remove local files older than 30 days
./manage.sh restart
```

## Script Interaction

```
Installation:
  install.sh
    ├── Creates directories
    ├── Copies scripts to ~/scripts
    └── Creates LaunchAgent plist

Auto-start (on login):
  LaunchAgent (com.keeboarder.recorder.plist)
    └── Runs: ~/scripts/start_recorder.sh
        ├── Captures screen
        ├── Captures microphone
        └── Captures system audio

Manual Commands:
  manage.sh
    ├── start    → LaunchAgent load
    ├── stop     → LaunchAgent unload
    ├── status   → Show current state
    ├── logs     → Display recent logs
    └── cleanup  → Remove old local files

Uploading (separate client):
  apps/jarvis/clients/syncer/syncer.sh
    └── Drains completed segments to the bucket proxy
```

## Logging Details

All scripts log to `~/recordings/logs/`.

**Timestamp format**: `YYYY-MM-DD HH:MM:SS`
**Log levels**: DEBUG, INFO, WARN, ERROR

Control logging in `config.sh`:

```bash
LOG_LEVEL="INFO"          # Change to DEBUG for more detail
LOG_RETENTION_DAYS="30"   # Keep logs for 30 days
```

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
- Nothing recording: `tail -f ~/recordings/logs/recorder.log`

See `TROUBLESHOOTING.md` for the longer list.

## Performance Notes

- Screen recording at 15 FPS, CRF 28
- Files segment hourly
- Local recording and remote streaming are separate FFmpeg jobs
