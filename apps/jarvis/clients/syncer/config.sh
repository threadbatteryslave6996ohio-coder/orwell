#!/bin/bash
# Configuration for the Keeboarder "syncer" client.
#
# The syncer drains completed recording segments to the bucket proxy. For every
# recording type it leaves the single most recent (still in-progress) segment
# untouched and merges all older, completed segments into one file before
# uploading it. Customize the values below before running syncer.sh.

# ============================================================
# Proxy Configuration
# ============================================================

# The syncer only talks to the bucket proxy (it never calls the AWS CLI). The
# proxy handles the credential exchange with the auth server.
PROXY_URL="http://localhost:5000"
PROXY_USERNAME="tester"
PROXY_PASSWORD="testsecret"

# Object-key prefix used when building the proxy folder path:
#   ${S3_PREFIX}${DEVICE_HOSTNAME}/<recording-type>
S3_PREFIX="recordings/"

# ============================================================
# Recording Directories
# ============================================================

RECORDINGS_DIR="$HOME/recordings"
SCREEN_DIR="$RECORDINGS_DIR/screen"
MIC_DIR="$RECORDINGS_DIR/mic"
SYSTEM_AUDIO_DIR="$RECORDINGS_DIR/system-audio"
LOGS_DIR="$RECORDINGS_DIR/logs"

# Scratch area where merged files are assembled before upload.
WORK_DIR="$RECORDINGS_DIR/.syncer-work"

# Token cache shared with the same auth flow used by the recorder uploader.
PROXY_TOKEN_FILE="$LOGS_DIR/.proxy_token"

# ============================================================
# Recording File Types
# ============================================================
#
# Screen segments may be MP4 (x11 / default) or WebM (GNOME Wayland). Files of a
# different container are merged independently — only same-container segments are
# concatenated together.
SCREEN_EXTENSIONS="mp4 webm"
MIC_EXTENSIONS="wav"
SYSTEM_AUDIO_EXTENSIONS="wav"

# ============================================================
# Sync Behaviour
# ============================================================

# Ignore segments younger than this (seconds). The most recent segment per type
# is always excluded regardless; this guards against a freshly rotated file that
# is still being flushed.
MIN_FILE_AGE="5"

# Wait this long and re-check size to confirm a segment is no longer changing
# before it is included in a merge.
FILE_STABILITY_WAIT_SECONDS="3"

# Remove the local source segments after their merged file uploads successfully.
# When false, sources are kept and re-merged on the next run (the merged object
# name is derived from the segment time range, so an unchanged range overwrites
# the same proxy object).
DELETE_AFTER_UPLOAD="true"

# Validate each source segment with ffprobe before including it in a merge.
VALIDATE_MEDIA_BEFORE_UPLOAD="true"

# ============================================================
# Advanced
# ============================================================

DEVICE_HOSTNAME="$(hostname)"

export PROXY_URL PROXY_USERNAME PROXY_PASSWORD S3_PREFIX PROXY_TOKEN_FILE
export RECORDINGS_DIR SCREEN_DIR MIC_DIR SYSTEM_AUDIO_DIR LOGS_DIR WORK_DIR
export SCREEN_EXTENSIONS MIC_EXTENSIONS SYSTEM_AUDIO_EXTENSIONS
export MIN_FILE_AGE FILE_STABILITY_WAIT_SECONDS DELETE_AFTER_UPLOAD VALIDATE_MEDIA_BEFORE_UPLOAD
export DEVICE_HOSTNAME
