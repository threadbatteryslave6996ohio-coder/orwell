# Keeboarder macOS Client - File Index

Complete documentation of all files in the macOS client.

## Core Scripts

### `start_recorder.sh`
**Purpose**: Main FFmpeg recording script
**What it does**:
- Records screen capture (MP4, H.264 compressed)
- Records microphone audio (WAV, PCM)
- Records system audio (WAV, PCM via BlackHole)
- Creates hourly segmented files
- Auto-restarts if processes crash
- Logs all activity

**How it runs**: 
- Launched by LaunchAgent on login
- Can also run manually: `bash ~/scripts/start_recorder.sh`

**Output**:
- `~/recordings/screen/*.mp4` - Screen recordings
- `~/recordings/mic/*.wav` - Microphone recordings
- `~/recordings/system-audio/*.wav` - System audio
- `~/recordings/logs/recorder.log` - Detailed logs

---

### `upload_to_s3.sh`
**Purpose**: Upload recorded files to AWS S3
**What it does**:
- Finds completed local recording files
- Skips files that are too new (still recording)
- Uploads each file with metadata
- Verifies upload success
- Optionally deletes local copy after upload
- Sends alerts on failure

**How it runs**:
- Runs periodically via cron (default: every 15 minutes)
- Can run manually: `./manage.sh upload`

**Configuration**:
- `UPLOAD_BATCH_SIZE` - Number of files to upload per run
- `MIN_FILE_AGE` - Minimum file age before uploading
- `DELETE_AFTER_UPLOAD` - Delete local files after successful upload
- `ALERT_EMAIL` - Email for failure alerts

**Output**:
- Uploads to `s3://bucket/recordings/HOSTNAME/{screen,microphone,system-audio}/`
- `~/recordings/logs/uploader.log` - Upload logs
- `~/recordings/logs/.upload_marker` - Upload history

---

### `verify_uploads.sh`
**Purpose**: Verify all local files exist on S3
**What it does**:
- Compares local files with S3 bucket
- Checks file sizes match
- Reports missing files
- Reports size mismatches
- Generates verification report
- Sends alerts if issues found

**How it runs**:
- Runs periodically via cron (default: every hour)
- Can run manually: `./manage.sh verify`

**Configuration**:
- `VERIFY_FILE_SIZE` - Check if file sizes match
- `ALERT_EMAIL` - Email for alerts

**Output**:
- `~/recordings/logs/verification.log` - Verification logs
- `~/recordings/logs/verification_report.txt` - Detailed report

---

### `manage.sh`
**Purpose**: Command-line management tool for all services
**What it does**:
- Start/stop/restart all services
- Show system status and disk usage
- View logs
- Run manual upload/verification
- Clean up old files
- Show AWS configuration status

**Usage**:
```bash
./manage.sh [command]
./manage.sh status              # Show current status
./manage.sh start               # Start services
./manage.sh stop                # Stop services
./manage.sh restart             # Restart services
./manage.sh logs                # View recent logs
./manage.sh upload              # Manual upload
./manage.sh verify              # Manual verification
./manage.sh cleanup [days]      # Remove old files
./manage.sh full-status         # Detailed report
```

---

### `setup-launchagent.sh`
**Purpose**: Standalone LaunchAgent management
**What it does**:
- Create LaunchAgent plist file
- Load/unload LaunchAgent
- Restart LaunchAgent
- Show LaunchAgent status
- View LaunchAgent logs

**Usage**:
```bash
./setup-launchagent.sh [command]
./setup-launchagent.sh load      # Load service
./setup-launchagent.sh unload    # Unload service
./setup-launchagent.sh restart   # Restart service
./setup-launchagent.sh status    # Show status
./setup-launchagent.sh logs      # View logs
```

---

### `install.sh`
**Purpose**: Complete installation and setup script
**What it does**:
- Checks for required tools (FFmpeg, AWS CLI)
- Verifies AWS credentials
- Checks S3 bucket access
- Verifies FFmpeg devices
- Creates directories
- Copies scripts to ~/scripts
- Sets up LaunchAgent for auto-start
- Configures cron jobs for uploads/verification
- Provides setup completion summary

**Usage**:
```bash
chmod +x install.sh
./install.sh
```

---

## Configuration Files

### `config.sh`
**Purpose**: Central configuration for all scripts
**Contains**:
- AWS S3 bucket and region
- Recording directories
- FFmpeg device names and settings
- Upload/verification settings
- Logging configuration
- Advanced settings

**Edit with**:
```bash
nano config.sh
```

**Key settings to customize**:
- `S3_BUCKET` - Your S3 bucket name
- `SCREEN_DEVICE` - Your screen device name
- `MIC_DEVICE` - Your microphone device name
- `SYSTEM_AUDIO_DEVICE` - Your BlackHole device name
- `DELETE_AFTER_UPLOAD` - Keep or delete local files

---

## Documentation Files

### `README.md`
**Comprehensive guide covering**:
- Feature overview
- Prerequisites and installation
- System permissions setup
- File structure
- Scripts documentation
- Manual commands
- Troubleshooting
- Performance notes
- Security notes
- AWS setup instructions

**Read for**: Understanding the full system

---

### `QUICKSTART.md`
**Quick reference guide**:
- 5-minute setup
- Prerequisites
- Step-by-step instructions
- Common commands
- Basic troubleshooting
- File locations

**Read for**: Fast setup and reference

---

### `AWS_SETUP.md`
**Complete AWS configuration guide**:
- Create S3 bucket
- Configure IAM user
- Set bucket policies
- Enable encryption
- Configure lifecycle policies
- Test setup
- Security best practices
- Cost estimation

**Read for**: Setting up AWS infrastructure

---

### `TROUBLESHOOTING.md`
**Comprehensive troubleshooting guide**:
- Quick diagnostics
- Recording issues
- S3 upload issues
- Verification issues
- Performance issues
- Service management issues
- Permissions issues
- Debugging techniques
- Getting help

**Read for**: Solving problems

---

### `FILE_INDEX.md` (this file)
**Complete file documentation**:
- Purpose of each file
- How each script works
- Configuration options
- Usage examples
- File locations

**Read for**: Understanding the system

---

## System Files

### `.gitignore`
**Purpose**: Git ignore patterns
**Ignores**:
- Recording files (*.mp4, *.wav)
- Log files
- Local directories created at runtime
- OS and IDE files
- Temporary files

---

## Directory Structure

After installation, you'll have:

```
~/scripts/
├── start_recorder.sh      # Main recorder (copied from this directory)
├── upload_to_s3.sh        # S3 uploader (copied from this directory)
├── verify_uploads.sh      # Verification (copied from this directory)
└── config.sh              # Configuration (copied from this directory)

~/Library/LaunchAgents/
└── com.keeboarder.recorder.plist  # Auto-start on login

~/recordings/
├── screen/                # Screen recordings (MP4)
├── mic/                   # Microphone recordings (WAV)
├── system-audio/          # System audio (WAV)
└── logs/
    ├── recorder.log       # Recorder activity
    ├── uploader.log       # Upload activity
    ├── verification.log   # Verification checks
    ├── launchagent.log    # LaunchAgent output
    ├── launchagent_error.log  # LaunchAgent errors
    ├── cron_uploader.log  # Cron upload logs
    ├── cron_verification.log   # Cron verification logs
    ├── .upload_marker     # Upload history
    └── verification_report.txt # Latest verification report
```

---

## Quick Reference

### Installation
```bash
cd ~/Desktop/keeboarder/clients/mac
chmod +x install.sh
./install.sh
```

### Start Recording
```bash
launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist
```

### Stop Recording
```bash
launchctl unload ~/Library/LaunchAgents/com.keeboarder.recorder.plist
```

### Check Status
```bash
./manage.sh status
```

### View Logs
```bash
./manage.sh logs
```

### Manual Upload
```bash
./manage.sh upload
```

### Manual Verification
```bash
./manage.sh verify
```

### Clean Up Old Files
```bash
./manage.sh cleanup 7
```

---

## Script Interaction

```
Installation:
  install.sh
    ├── Creates directories
    ├── Copies scripts to ~/scripts
    ├── Creates LaunchAgent plist
    └── Sets up cron jobs

Auto-start (on login):
  LaunchAgent (com.keeboarder.recorder.plist)
    └── Runs: ~/scripts/start_recorder.sh
        ├── Captures screen
        ├── Captures microphone
        └── Captures system audio

Periodic Tasks:
  Cron Jobs
    ├── Every 15 min: ~/scripts/upload_to_s3.sh
    │   └── Uploads files to S3
    └── Every 1 hour: ~/scripts/verify_uploads.sh
        └── Verifies files on S3

Manual Commands:
  manage.sh
    ├── start    → LaunchAgent load
    ├── stop     → LaunchAgent unload
    ├── upload   → Run upload_to_s3.sh manually
    ├── verify   → Run verify_uploads.sh manually
    ├── status   → Show current state
    └── logs     → Display recent logs
```

---

## Configuration Hierarchy

Settings are read from `config.sh` in this order:

1. **Hardcoded defaults** in each script
2. **config.sh** values (override defaults)
3. **Environment variables** (if set, override config.sh)

Example: To temporarily use a different S3 bucket:
```bash
export S3_BUCKET="my-other-bucket"
./manage.sh upload
```

---

## Logging Details

All scripts log to `~/recordings/logs/`:

**Timestamp format**: `YYYY-MM-DD HH:MM:SS`
**Log levels**: DEBUG, INFO, WARN, ERROR

**Control logging**:
```bash
# In config.sh:
LOG_LEVEL="INFO"  # Change to DEBUG for more detail
LOG_RETENTION_DAYS="30"  # Keep logs for 30 days
```

---

## Common Workflows

### Workflow 1: Initial Setup
1. Run `./install.sh`
2. Grant system permissions (System Settings)
3. Verify with `./manage.sh status`
4. Check logs with `./manage.sh logs`

### Workflow 2: Daily Monitoring
1. Check status: `./manage.sh status`
2. View upload logs: `./manage.sh logs uploader`
3. Verify uploads: `./manage.sh verify`

### Workflow 3: Cleanup
1. Check disk usage: `./manage.sh status`
2. Remove old files: `./manage.sh cleanup 14`
3. Verify S3 has backups: `./manage.sh verify`

### Workflow 4: Troubleshooting
1. Run `./manage.sh full-status`
2. Check relevant logs in `~/recordings/logs/`
3. See TROUBLESHOOTING.md for detailed help

---

## Support Resources

1. **README.md** - Complete documentation
2. **QUICKSTART.md** - Fast reference
3. **AWS_SETUP.md** - AWS configuration
4. **TROUBLESHOOTING.md** - Problem solving
5. **FILE_INDEX.md** (this file) - File reference
6. **config.sh** - Configuration options
7. **Logs** - `~/recordings/logs/`

---

## Version Information

**Last Updated**: 2024-01-15
**Keeboarder Version**: 1.0
**macOS Support**: 10.13+
**Tested with**: macOS 12, 13, 14

---
