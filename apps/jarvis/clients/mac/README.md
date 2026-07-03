# Keeboarder macOS Client - FFmpeg + AWS S3

A comprehensive macOS recording client that captures screen, microphone, and system audio using FFmpeg, automatically starts on login, and syncs all recordings to AWS S3.

## Features

- **Screen Recording**: Captures display at 15 FPS, compressed with H.264
- **Microphone Recording**: Records user audio in WAV format
- **System Audio**: Captures system audio through BlackHole virtual audio device
- **Auto-Start**: Launches automatically on login via LaunchAgent
- **AWS S3 Integration**: Periodically uploads recordings to S3
- **Optional Remote Stream**: Can mirror screen capture to a configurable live ingest endpoint
- **Verification System**: Ensures all local files are successfully uploaded
- **Logging**: Comprehensive logs for debugging and monitoring

## Prerequisites

1. **Homebrew** (if not installed):
   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```

2. **Required Tools**:
   ```bash
   brew install ffmpeg
   brew install --cask blackhole-2ch
   brew install aws-cli
   ```

3. **AWS Credentials**: Set up AWS CLI with your credentials
   ```bash
   aws configure
   ```
   You'll need:
   - AWS Access Key ID
   - AWS Secret Access Key
   - Default region (e.g., us-east-1)
   - Default output format (json)

4. **S3 Bucket**: Create an S3 bucket for recordings (or use existing one)

## Installation

### 1. Find Device Names

First, verify your device names for FFmpeg:

```bash
ffmpeg -f avfoundation -list_devices true -i ""
```

Look for:
- `Capture screen 0`
- `MacBook Microphone` (or similar microphone name)
- `BlackHole 2ch`

Save the exact device names for use in configuration.

### 2. Clone/Setup

```bash
cd ~/Desktop/keeboarder/clients/mac
chmod +x install.sh
./install.sh
```

### 3. Configure

Edit `config.sh` and update:
- AWS S3 bucket name
- Recording directories
- Device names (if different from defaults)
- Upload frequency

### 4. Verify Setup

```bash
./setup-launchagent.sh
launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist
```

## System Permissions

After installation, grant permissions in **System Settings**:

1. Go to **Settings → Privacy & Security**
2. Under **Screen Recording**: Add Terminal / iTerm2
3. Under **Microphone**: Add Terminal / iTerm2
4. Under **Full Disk Access**: Add Terminal / iTerm2 (for S3 uploads)

## File Structure

```
~/recordings/
├── screen/           # Screen recordings (MP4)
├── mic/              # Microphone recordings (WAV)
├── system-audio/     # System audio recordings (WAV)
└── logs/             # All log files
    ├── recorder.log
    ├── uploader.log
    └── verification.log
```

## Scripts

### start_recorder.sh
Main FFmpeg recording script. Captures screen, microphone, and system audio in parallel.

**Runs**: On login via LaunchAgent
**Output**: Hourly fragmented MP4/WAV files in respective directories
Live streaming is enabled by default once `REMOTE_STREAM_URL` is configured. A separate supervised screen-stream process publishes to the endpoint, so it and the local recorder restart independently.

### upload_to_s3.sh
Uploads recorded files to AWS S3 bucket.

**Runs**: Every 15 minutes (via cron)
**Features**:
- Skips files that are less than 5 seconds old (still recording)
- Adds metadata (timestamp, device info)
- Retains local files after successful upload by default
- Checks video existence and size remotely before uploading, avoiding duplicate uploads
- Supports a video-only sync pass through `sync_videos.sh` or `./manage.sh sync`
- Serializes sync/upload jobs and skips files still open by FFmpeg. The file signature is checked again immediately before transfer; systems without open-file inspection use a configurable stability window.
- Creates verification logs

### verify_uploads.sh
Checks that all local files exist on S3.

**Runs**: Hourly (via cron)
**Checks**:
- All local files have S3 counterparts
- File sizes match
- Reports missing/mismatched files
- Alerts if discrepancies found

### setup-launchagent.sh
Sets up auto-start configuration.

## Manual Commands

### Start Recording
```bash
launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist
```

### Stop Recording
```bash
launchctl unload ~/Library/LaunchAgents/com.keeboarder.recorder.plist
pkill -f start_recorder
pkill -f ffmpeg
```

### Check Status
```bash
launchctl list | grep keeboarder
ps aux | grep ffmpeg
ps aux | grep recorder
```

### View Logs
```bash
# Recorder logs
tail -f ~/recordings/logs/recorder.log

# Uploader logs
tail -f ~/recordings/logs/uploader.log

# Verification logs
tail -f ~/recordings/logs/verification.log

# LaunchAgent system logs
log stream --predicate 'process == "start_recorder.sh"'
```

### Manual Upload
```bash
~/scripts/upload_to_s3.sh
```

### Manual Verification
```bash
~/scripts/verify_uploads.sh
```

## Troubleshooting

### FFmpeg Not Recording
- Verify permissions: System Settings → Privacy & Security → Screen Recording
- Check device names: `ffmpeg -f avfoundation -list_devices true -i ""`
- Test FFmpeg manually: `ffmpeg -f avfoundation -i "Capture screen 0:none" -t 5 test.mp4`

### S3 Upload Failing
- Verify AWS credentials: `aws s3 ls`
- Check bucket exists: `aws s3 ls s3://your-bucket-name`
- Check IAM permissions: User needs `s3:PutObject`, `s3:GetObject`, `s3:ListBucket`

### LaunchAgent Not Starting
- Check if loaded: `launchctl list | grep keeboarder`
- Check system logs: `log stream --predicate 'process == "start_recorder"'`
- Reload: `launchctl unload ~/Library/LaunchAgents/com.keeboarder.recorder.plist && launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist`

### Cron Jobs Not Running
- Verify crontab: `crontab -l`
- Check cron logs: `log stream --predicate 'process == "cron"'`
- Ensure Full Disk Access is granted to Terminal/iTerm2

## Performance Notes

- Screen recording at 15 FPS, CRF 28 (quality/compression balance)
- Files segment hourly to prevent large single files
- Upload checks file age (5s) to avoid uploading incomplete files
- All operations run in background; minimal system impact
- Local recording and remote streaming are separate FFmpeg jobs, so one can fail without taking down the other

## Security Notes

- Store AWS credentials securely via `aws configure`
- Consider using AWS IAM roles if running on EC2/other AWS resources
- Recordings contain sensitive data; restrict S3 bucket access
- Enable S3 encryption (SSE) for bucket
- Consider setting S3 lifecycle policies to archive old recordings

## Optional Remote Stream

To mirror the screen capture to a live endpoint, set these in `config.sh`:

```bash
REMOTE_STREAM_ENABLED="true"
REMOTE_STREAM_URL="rtmp://stream.example.com/live"
REMOTE_STREAM_FORMAT="flv"
```

An empty `REMOTE_STREAM_URL` logs a warning and does not affect local recording. Set `DELETE_AFTER_UPLOAD="true"` only when synchronized local copies should be removed.

Use `flv` for RTMP/RTMPS and `mpegts` for SRT/UDP-style ingest endpoints. The stream job is isolated from local recording, so if the endpoint goes down the saved files keep writing.

## AWS S3 Bucket Setup

Create a bucket with proper settings:

```bash
# Create bucket
aws s3 mb s3://keeboarder-recordings --region us-east-1

# Enable versioning (optional, for recovery)
aws s3api put-bucket-versioning \
  --bucket keeboarder-recordings \
  --versioning-configuration Status=Enabled

# Enable encryption
aws s3api put-bucket-encryption \
  --bucket keeboarder-recordings \
  --server-side-encryption-configuration '{
    "Rules": [{
      "ApplyServerSideEncryptionByDefault": {
        "SSEAlgorithm": "AES256"
      }
    }]
  }'

# Set bucket policy (restrict access)
# Create a policy JSON and apply it
```

## Support

For issues or improvements, refer to the Keeboarder documentation or contact the development team.
