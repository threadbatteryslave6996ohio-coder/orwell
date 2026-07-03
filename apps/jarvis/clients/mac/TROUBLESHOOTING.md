# Keeboarder macOS - Troubleshooting Guide

Complete troubleshooting for common issues.

## Quick Diagnostics

Run this command to get a full system report:

```bash
./manage.sh full-status
```

Then check the relevant section below.

## Recording Issues

### FFmpeg Not Recording

**Check 1: Permissions**
```bash
# Go to System Settings → Privacy & Security
# Verify Terminal/iTerm2 has "Screen Recording" permission
# Also check Microphone permission
```

**Check 2: Device Names**
```bash
ffmpeg -f avfoundation -list_devices true -i ""
```

Compare output with config.sh:
```bash
grep "DEVICE=" config.sh
```

**Check 3: Test FFmpeg Manually**
```bash
# Test screen recording
ffmpeg -f avfoundation -framerate 15 -i "Capture screen 0:none" -t 10 test.mp4

# Test microphone
ffmpeg -f avfoundation -i ":MacBook Microphone" -t 10 test.wav

# Test system audio
ffmpeg -f avfoundation -i ":BlackHole 2ch" -t 10 test.wav
```

**Check 4: Verify LaunchAgent is Running**
```bash
launchctl list | grep keeboarder
ps aux | grep ffmpeg
ps aux | grep start_recorder
```

**Check 5: View Recorder Logs**
```bash
tail -100 ~/recordings/logs/recorder.log
tail -50 ~/recordings/logs/launchagent.log
```

### Recording Files Not Being Created

**Check 1: Directory Permissions**
```bash
ls -la ~/recordings/
chmod 755 ~/recordings/{screen,mic,system-audio,logs}
```

**Check 2: Disk Space**
```bash
df -h ~/
du -sh ~/recordings/
```

If low on space, clean up:
```bash
./manage.sh cleanup 7  # Remove files > 7 days old
```

**Check 3: FFmpeg Codec Support**
```bash
ffmpeg -codecs | grep libx264
ffmpeg -codecs | grep pcm_s16le
```

### Files Too Large or Small

**Problem**: Files are suspiciously small or not growing
- Check CRF and preset settings in config.sh
- Lower CRF for better quality: `SCREEN_CRF="20"` (higher file size)
- Increase preset speed: `SCREEN_PRESET="fast"`

**Problem**: Files are empty or corrupted
- Usually means FFmpeg crashed
- Check logs: `tail -f ~/recordings/logs/recorder.log`
- Verify permissions again

## S3 Upload Issues

### AWS Credentials Not Working

**Check 1: Verify Credentials**
```bash
aws sts get-caller-identity
```

Should return:
```json
{
    "UserId": "AIDAI...",
    "Account": "123456789012",
    "Arn": "arn:aws:iam::123456789012:user/keeboarder-recorder"
}
```

**Check 2: Configure/Reconfigure**
```bash
aws configure
# Or for specific profile:
aws configure --profile keeboarder
```

**Check 3: Check Environment Variables**
```bash
echo $AWS_ACCESS_KEY_ID
echo $AWS_SECRET_ACCESS_KEY
echo $AWS_DEFAULT_REGION
```

If not set, add to `~/.bash_profile` or `~/.zshrc`:
```bash
export AWS_ACCESS_KEY_ID="AKIAIOSFODNN7EXAMPLE"
export AWS_SECRET_ACCESS_KEY="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
export AWS_DEFAULT_REGION="us-east-1"
```

### Cannot Access S3 Bucket

**Check 1: Bucket Exists and is Accessible**
```bash
aws s3 ls | grep keeboarder
aws s3 ls s3://keeboarder-recordings
```

**Check 2: IAM Permissions**
```bash
# Check inline policy
aws iam list-user-policies --user-name keeboarder-recorder
```

Required actions:
- `s3:PutObject`
- `s3:GetObject`
- `s3:ListBucket`

See AWS_SETUP.md for IAM configuration.

**Check 3: Verify Region**
```bash
# Check what region is configured
aws configure get region

# Check bucket region
aws s3api get-bucket-location --bucket keeboarder-recordings
```

Update config.sh if regions don't match:
```bash
AWS_REGION="us-east-1"
```

**Check 4: Test Upload Manually**
```bash
echo "test" > /tmp/test.txt
aws s3 cp /tmp/test.txt s3://keeboarder-recordings/test.txt
```

### Upload Jobs Not Running

**Check 1: Verify Cron Jobs**
```bash
crontab -l
```

Should show:
```
*/15 * * * * /Users/username/scripts/upload_to_s3.sh
0 * * * * /Users/username/scripts/verify_uploads.sh
```

**Check 2: Reinstall Cron Jobs**
```bash
# Back up current crontab
crontab -l > crontab_backup.txt

# Clear crontab
crontab -r

# Re-run installer
./install.sh
```

**Check 3: Check Cron Logs**
```bash
# View cron execution logs
log stream --predicate 'eventMessage contains[cd] "upload_to_s3"' --level debug

# Or check system logs
log show --predicate 'process == "cron"' --last 1h
```

**Check 4: Manual Upload Test**
```bash
# Run upload script manually
bash ~/scripts/upload_to_s3.sh

# Check logs
tail -50 ~/recordings/logs/uploader.log
```

### File Upload Permissions Denied

**Error**: "Access Denied" or "InvalidAccessKeyId"

**Solution**:
1. Verify IAM user has s3:PutObject permission
2. Check access key is valid and not deactivated
3. Check bucket policy allows the IAM user
4. Verify bucket doesn't have ACL restrictions

See AWS_SETUP.md section "Set Bucket Policies"

### Some Files Not Uploading

**Check 1: File Age Filter**
Files must be at least 5 seconds old before uploading (to avoid incomplete files)

```bash
# Change minimum age if needed
MIN_FILE_AGE="10"  # in config.sh
```

**Check 2: File Size Check**
Files smaller than 1KB are skipped:

```bash
# Check file sizes
ls -lh ~/recordings/screen/*.mp4
ls -lh ~/recordings/mic/*.wav
```

**Check 3: View Uploader Logs**
```bash
tail -100 ~/recordings/logs/uploader.log
```

**Check 4: Manually Upload Specific File**
```bash
aws s3 cp ~/recordings/screen/screen-2024-01-15-14.mp4 \
  s3://keeboarder-recordings/recordings/$(hostname)/screen/ \
  --metadata "uploaded=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
```

## Verification Issues

### Verification Jobs Not Running

Same as upload jobs, see section above.

### Verification Showing Missing Files

**Check 1: Verify Recent Uploads**
```bash
# Check what's on S3
aws s3 ls s3://keeboarder-recordings/recordings/$(hostname)/ --recursive | tail -20

# Check what's local
ls -lh ~/recordings/screen/*.mp4 | tail -10
ls -lh ~/recordings/mic/*.wav | tail -10
```

**Check 2: Run Manual Verification**
```bash
./manage.sh verify
cat ~/recordings/logs/verification_report.txt
```

**Check 3: File Size Mismatch**
This can happen if:
- File was still being written when uploaded
- Network interruption during upload
- S3 object corruption

**Solution**: Re-upload the file:
```bash
aws s3 cp ~/recordings/screen/screen-XXXX.mp4 \
  s3://keeboarder-recordings/recordings/$(hostname)/screen/
```

## Performance Issues

### High CPU Usage

**Check 1: FFmpeg Process**
```bash
ps aux | grep ffmpeg | grep -v grep
top -b -n 1 | grep -i ffmpeg
```

**Solution**: Reduce quality or frame rate in config.sh:
```bash
SCREEN_FPS="10"  # Lower from 15
SCREEN_CRF="32"  # Lower quality (faster)
SCREEN_PRESET="superfast"  # Faster encoding
```

### High Disk Usage

**Check 1: Current Disk Usage**
```bash
du -sh ~/recordings/
du -sh ~/recordings/{screen,mic,system-audio}
```

**Solution**: Clean up old files:
```bash
./manage.sh cleanup 7  # Remove files > 7 days old
./manage.sh cleanup 1  # Remove files > 1 day old
```

Or enable S3 lifecycle policies to auto-delete old files.

### Network/Upload Slow

**Check 1: Network Speed**
```bash
# Test network connectivity
ping -c 5 8.8.8.8
curl -I https://www.amazon.com

# Test S3 upload speed
time aws s3 cp ~/recordings/screen/screen-*.mp4 \
  s3://keeboarder-recordings/recordings/$(hostname)/screen/ \
  --recursive --exclude "*" --include "*-14.mp4" | head -1
```

**Solution**: Increase upload frequency or batch size:
```bash
# config.sh
UPLOAD_BATCH_SIZE="20"  # Upload more files per run
UPLOAD_CRON="*/10 * * * *"  # Upload every 10 minutes instead of 15
```

## Service Management Issues

### LaunchAgent Not Starting on Login

**Check 1: Verify Plist File**
```bash
launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist
```

**Check 2: Validate Plist Syntax**
```bash
plutil -lint ~/Library/LaunchAgents/com.keeboarder.recorder.plist
```

**Check 3: Recreate Plist**
```bash
./setup-launchagent.sh create
./setup-launchagent.sh load
```

**Check 4: System Logs**
```bash
log stream --predicate 'eventMessage contains[cd] "com.keeboarder.recorder"'
```

### Service Crashes/Restarts

**Check 1: View Crash Logs**
```bash
tail -50 ~/recordings/logs/launchagent_error.log
tail -50 ~/recordings/logs/recorder.log
```

**Check 2: Common Causes**
- No permissions (check System Settings)
- Device disconnected (USB mic, external monitor)
- Insufficient disk space
- FFmpeg crash

**Solution**: 
1. Fix the underlying issue
2. Restart service: `./manage.sh restart`

## System Permissions Issues

### "Operation not permitted" Errors

**Solution 1: Grant Full Disk Access**
1. System Settings → Privacy & Security → Full Disk Access
2. Click `+` button
3. Select your Terminal app
4. Click "Open"

**Solution 2: Grant Screen Recording Permission**
1. System Settings → Privacy & Security → Screen Recording
2. Click `+` button
3. Select your Terminal app
4. Click "Open"

**Solution 3: Grant Microphone Permission**
1. System Settings → Privacy & Security → Microphone
2. Click `+` button
3. Select your Terminal app
4. Click "Open"

### Terminal App Not in Permissions List

Use this command to open Terminal or iTerm2:
```bash
# For Terminal.app
open /Applications/Utilities/Terminal.app

# For iTerm2
open /Applications/iTerm.app
```

Then manually add them to System Settings.

## Debugging Advanced Issues

### Enable Debug Mode

```bash
# Edit config.sh
nano config.sh

# Change to:
DEBUG_MODE="true"
LOG_LEVEL="DEBUG"

# Restart services
./manage.sh restart

# View detailed logs
tail -100 ~/recordings/logs/recorder.log
```

### Run Individual Scripts Manually

```bash
# Test recorder
bash ~/scripts/start_recorder.sh

# Test uploader
bash ~/scripts/upload_to_s3.sh

# Test verification
bash ~/scripts/verify_uploads.sh
```

### Check System Resources

```bash
# Memory usage
vm_stat

# CPU usage
top -b -n 1 | head -15

# Disk usage
df -h
du -sh ~/recordings/*

# Network
networksetup -getinfo Wi-Fi
```

### Verify AWS CLI Connection

```bash
# Test S3 access
aws s3 ls --region us-east-1 --debug 2>&1 | head -50

# Test with specific profile
aws s3 ls --profile keeboarder --debug 2>&1 | head -50
```

## Getting Help

**Gather Diagnostic Info**:
```bash
# Create diagnostics file
{
  echo "=== System Info ==="
  uname -a
  echo ""
  echo "=== Tools ==="
  ffmpeg -version | head -1
  aws --version
  echo ""
  echo "=== Configuration ==="
  cat config.sh | grep -E "^[A-Z_]+="
  echo ""
  echo "=== Cron Jobs ==="
  crontab -l
  echo ""
  echo "=== Permissions ==="
  ls -la ~/Library/LaunchAgents/com.keeboarder.recorder.plist
  echo ""
  echo "=== Recent Logs ==="
  tail -20 ~/recordings/logs/recorder.log
  tail -20 ~/recordings/logs/uploader.log
} > ~/keeboarder_diagnostics.txt

# Review and share
cat ~/keeboarder_diagnostics.txt
```

Check this file against the README.md and AWS_SETUP.md documentation.
