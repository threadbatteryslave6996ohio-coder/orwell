# Keeboarder macOS - Troubleshooting Guide

Complete troubleshooting for common issues with this **record-only** client. Uploading is handled
by the separate syncer client at `apps/jarvis/clients/syncer/` — see its own README for
upload/sync troubleshooting.

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

## Uploading Recordings

Uploads are not performed by this client. Completed recording segments are drained to the bucket
proxy by the separate syncer client at `apps/jarvis/clients/syncer/`. If recordings are not
reaching the bucket, check that the syncer is scheduled and configured correctly and consult
`apps/jarvis/clients/syncer/README.md`.

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

### Run the Recorder Manually

```bash
# Test recorder
bash ~/scripts/start_recorder.sh
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
  echo ""
  echo "=== Configuration ==="
  cat config.sh | grep -E "^[A-Z_]+="
  echo ""
  echo "=== Permissions ==="
  ls -la ~/Library/LaunchAgents/com.keeboarder.recorder.plist
  echo ""
  echo "=== Recent Logs ==="
  tail -20 ~/recordings/logs/recorder.log
} > ~/keeboarder_diagnostics.txt

# Review and share
cat ~/keeboarder_diagnostics.txt
```

Check this file against the README.md documentation.
