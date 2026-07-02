# Keeboarder macOS Client - Quick Start Guide

Get your macOS recording and S3 upload system running in 5 minutes.

## Prerequisites

- macOS 10.13+
- Homebrew installed
- AWS Account with S3 bucket

## Step 1: Install Dependencies (2 minutes)

```bash
brew install ffmpeg
brew install --cask blackhole-2ch
brew install awscli
```

## Step 2: Configure AWS (1 minute)

```bash
aws configure
```

Enter:
- AWS Access Key ID
- AWS Secret Access Key
- Default region (e.g., `us-east-1`)
- Output format: `json`

**Create S3 bucket if needed:**
```bash
aws s3 mb s3://keeboarder-recordings --region us-east-1
```

## Step 3: Verify Device Names (1 minute)

```bash
ffmpeg -f avfoundation -list_devices true -i ""
```

Look for:
- Screen device (e.g., "Capture screen 0")
- Microphone device (e.g., "MacBook Microphone")
- BlackHole device (e.g., "BlackHole 2ch")

## Step 4: Update Configuration (1 minute)

```bash
nano config.sh
```

Update these lines if your device names are different:
```bash
SCREEN_DEVICE="Capture screen 0"
MIC_DEVICE="MacBook Microphone"
SYSTEM_AUDIO_DEVICE="BlackHole 2ch"
S3_BUCKET="keeboarder-recordings"
AWS_REGION="us-east-1"
```

## Step 5: Run Installer (Auto setup - 30 seconds)

```bash
chmod +x install.sh
./install.sh
```

The installer will:
- ✓ Verify all tools are installed
- ✓ Check AWS credentials
- ✓ Create recording directories
- ✓ Setup LaunchAgent for auto-start
- ✓ Configure cron jobs for uploads & verification

## Step 6: Grant System Permissions (1 minute)

**Important:** Without these, recording won't work!

1. Open **System Settings** → **Privacy & Security**
2. Scroll down to **Screen Recording**
   - Click the `+` button
   - Select your Terminal app (e.g., Terminal, iTerm2)
3. Scroll to **Microphone**
   - Click the `+` button
   - Select your Terminal app
4. Optional: Add to **Full Disk Access** for better reliability

## Done! 🎉

Your system is now ready. Start recording:

```bash
# Start recording
launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist

# Check status
./manage.sh status

# View logs
tail -f ~/recordings/logs/recorder.log
```

## Common Commands

```bash
# Check current status and disk usage
./manage.sh status

# View all logs
./manage.sh logs

# Manually upload files to S3
./manage.sh upload

# Verify all files are on S3
./manage.sh verify

# Stop recording
launchctl unload ~/Library/LaunchAgents/com.keeboarder.recorder.plist

# Restart recording
./manage.sh restart
```

## Troubleshooting

### Recording not working?
1. Check permissions: System Settings → Privacy & Security → Screen Recording
2. Verify device names: `ffmpeg -f avfoundation -list_devices true -i ""`
3. Check logs: `tail -f ~/recordings/logs/recorder.log`

### Upload failing?
1. Verify credentials: `aws s3 ls`
2. Check bucket exists: `aws s3 ls s3://keeboarder-recordings`
3. Check logs: `tail -f ~/recordings/logs/uploader.log`

### S3 verification failing?
1. Check AWS permissions (need s3:PutObject, s3:GetObject, s3:ListBucket)
2. Run manual check: `./manage.sh verify`
3. View report: `cat ~/recordings/logs/verification_report.txt`

## File Locations

```
~/recordings/               # All recordings
  ├── screen/             # Screen videos (MP4)
  ├── mic/                # Microphone audio (WAV)
  ├── system-audio/       # System audio (WAV)
  └── logs/               # All logs
       ├── recorder.log
       ├── uploader.log
       └── verification.log

~/scripts/
  ├── start_recorder.sh    # Main recorder script
  ├── upload_to_s3.sh      # S3 uploader
  ├── verify_uploads.sh    # Verification checker
  └── config.sh            # Configuration
```

## Next Steps

- **Monitor**: Check `./manage.sh status` regularly
- **Configure Alerts**: Edit `config.sh` and set `ALERT_EMAIL`
- **Retention**: Review log retention settings in `config.sh`
- **AWS Security**: Enable S3 encryption, set bucket policies, consider IAM roles
- **Documentation**: See `README.md` for complete documentation

## Support

For issues, check:
1. Logs: `~/recordings/logs/`
2. Documentation: `README.md`
3. Configuration: `config.sh`
