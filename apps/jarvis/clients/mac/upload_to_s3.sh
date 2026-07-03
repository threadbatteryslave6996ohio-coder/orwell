#!/bin/bash
set -uo pipefail

# Keeboarder macOS S3 Uploader
# Uploads recorded files to AWS S3 bucket
# Runs periodically via cron (see config.sh for frequency)

# Source configuration
CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$CONFIG_DIR/config.sh"
: "${FILE_STABILITY_WAIT_SECONDS:=3}"

# Initialize logging
UPLOADER_LOG="$LOGS_DIR/uploader.log"
UPLOAD_MARKER_FILE="$LOGS_DIR/.upload_marker"

# Create directories if they don't exist
mkdir -p "$LOGS_DIR"

VIDEOS_ONLY="false"
if [ "${1:-}" = "--videos-only" ]; then
    VIDEOS_ONLY="true"
fi

# Logging function
log_message() {
    local level="$1"
    local message="$2"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$UPLOADER_LOG"
}

release_upload_lock() {
    rm -f "$UPLOAD_LOCK_DIR/pid"
    rmdir "$UPLOAD_LOCK_DIR" 2>/dev/null || true
}

acquire_upload_lock() {
    local lock_pid=""

    if mkdir "$UPLOAD_LOCK_DIR" 2>/dev/null; then
        printf '%s\n' "$$" > "$UPLOAD_LOCK_DIR/pid"
        trap release_upload_lock EXIT
        trap 'exit 130' INT TERM HUP
        return 0
    fi

    if [ -r "$UPLOAD_LOCK_DIR/pid" ]; then
        read -r lock_pid < "$UPLOAD_LOCK_DIR/pid" || true
    fi
    if [ -n "$lock_pid" ] && kill -0 "$lock_pid" 2>/dev/null; then
        log_message "INFO" "Another upload/sync process is already running (pid=$lock_pid); skipping this run"
        return 1
    fi

    log_message "WARN" "Removing stale upload/sync lock"
    rm -f "$UPLOAD_LOCK_DIR/pid"
    rmdir "$UPLOAD_LOCK_DIR" 2>/dev/null || return 1
    mkdir "$UPLOAD_LOCK_DIR" || return 1
    printf '%s\n' "$$" > "$UPLOAD_LOCK_DIR/pid"
    trap release_upload_lock EXIT
    trap 'exit 130' INT TERM HUP
}

file_signature() {
    stat -f '%z:%m' "$1" 2>/dev/null || stat -c '%s:%Y' "$1" 2>/dev/null
}

file_is_open() {
    local file="$1"

    if command -v lsof >/dev/null 2>&1; then
        lsof -t -- "$file" >/dev/null 2>&1
        return $?
    fi
    if command -v fuser >/dev/null 2>&1; then
        fuser "$file" >/dev/null 2>&1
        return $?
    fi
    return 2
}

recording_is_complete() {
    local file="$1"
    local open_status before after

    file_is_open "$file"
    open_status=$?
    if [ "$open_status" -eq 0 ]; then
        return 1
    fi
    if [ "$open_status" -eq 1 ]; then
        return 0
    fi

    before=$(file_signature "$file") || return 1
    sleep "$FILE_STABILITY_WAIT_SECONDS"
    after=$(file_signature "$file") || return 1
    [ "$before" = "$after" ]
}

file_is_unchanged_and_closed() {
    local file="$1"
    local expected_signature="$2"

    if file_is_open "$file"; then
        return 1
    fi
    [ "$(file_signature "$file")" = "$expected_signature" ]
}

# Function to upload a single file to S3
upload_file_to_s3() {
    local file="$1"
    local s3_path="$2"
    local device_name="$DEVICE_HOSTNAME"
    
    # Skip if file doesn't exist
    if [ ! -f "$file" ]; then
        return 1
    fi

    if ! recording_is_complete "$file"; then
        log_message "DEBUG" "Skipping $file (still open or changing)"
        return 2
    fi

    local initial_signature
    initial_signature=$(file_signature "$file") || return 2
    
    # Get file size in bytes
    local file_size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)
    
    # Skip files that are too small (likely still recording)
    if [ "$file_size" -lt 1024 ]; then
        log_message "DEBUG" "Skipping $file (too small: $file_size bytes)"
        return 2
    fi
    
    # Get file age in seconds
    local file_mtime=$(stat -f%m "$file" 2>/dev/null || stat -c%Y "$file" 2>/dev/null)
    local current_time=$(date +%s)
    local file_age=$((current_time - file_mtime))
    
    # Skip files that are too new (still being written)
    if [ "$file_age" -lt "$MIN_FILE_AGE" ]; then
        log_message "DEBUG" "Skipping $file (too new: $file_age seconds old)"
        return 2
    fi

    if ! file_is_unchanged_and_closed "$file" "$initial_signature"; then
        log_message "DEBUG" "Skipping $file (opened or changed during synchronization check)"
        return 2
    fi
    
    # Construct S3 path
    local basename=$(basename "$file")
    local s3_full_path="s3://$S3_BUCKET/${S3_PREFIX}${device_name}/${s3_path}/${basename}"

    if [ "$s3_path" = "screen" ] && [ "$CHECK_REMOTE_BEFORE_UPLOAD" = "true" ]; then
        local remote_size
        if remote_size=$(aws s3api head-object \
            --bucket "$S3_BUCKET" \
            --key "${s3_full_path#s3://$S3_BUCKET/}" \
            --region "$AWS_REGION" \
            ${AWS_PROFILE:+--profile "$AWS_PROFILE"} \
            --query 'ContentLength' \
            --output text 2>>"$UPLOADER_LOG"); then
            if [ "$remote_size" = "$file_size" ]; then
                if ! file_is_unchanged_and_closed "$file" "$initial_signature"; then
                    log_message "DEBUG" "Skipping $file (opened or changed during synchronization check)"
                    return 2
                fi
                log_message "INFO" "Already synchronized: $basename (remote size matches $file_size bytes)"
                if [ "$DELETE_AFTER_UPLOAD" = "true" ]; then
                    rm -f "$file"
                    log_message "DEBUG" "Deleted synchronized local file: $file"
                fi
                return 0
            fi
            log_message "INFO" "Remote video size differs; uploading: $basename"
        else
            log_message "INFO" "Remote video is missing; uploading: $basename"
        fi
    fi

    if ! file_is_unchanged_and_closed "$file" "$initial_signature"; then
        log_message "DEBUG" "Skipping $file (opened or changed during synchronization check)"
        return 2
    fi
    
    log_message "INFO" "Uploading: $basename to $s3_full_path (${file_size} bytes, age ${file_age}s)"
    
    # Add metadata and upload
    aws s3 cp "$file" "$s3_full_path" \
        --region "$AWS_REGION" \
        ${AWS_PROFILE:+--profile "$AWS_PROFILE"} \
        --metadata "uploaded=$(date -u +%Y-%m-%dT%H:%M:%SZ),device=$device_name,local_size=$file_size" \
        --sse AES256 \
        2>&1 | tee -a "$UPLOADER_LOG"
    
    if [ "${PIPESTATUS[0]}" -eq 0 ]; then
        log_message "INFO" "Successfully uploaded: $basename"
        
        # Delete local file if configured
        if [ "$DELETE_AFTER_UPLOAD" = "true" ]; then
            rm -f "$file"
            log_message "DEBUG" "Deleted local file: $file"
        fi
        
        return 0
    else
        log_message "ERROR" "Failed to upload: $basename"
        return 1
    fi
}

# Count uploads in this session
uploads_successful=0
uploads_failed=0
uploads_skipped=0
UPLOAD_LOCK_DIR="$LOGS_DIR/.upload-sync.lock"

log_message "INFO" "=== S3 Uploader Started ==="

if ! acquire_upload_lock; then
    exit 0
fi
log_message "DEBUG" "S3 Bucket: $S3_BUCKET"
log_message "DEBUG" "AWS Region: $AWS_REGION"
log_message "DEBUG" "Device: $DEVICE_HOSTNAME"

# Verify AWS CLI is installed
if ! command -v aws &> /dev/null; then
    log_message "ERROR" "AWS CLI not found. Please install: brew install awscli"
    exit 1
fi

# Verify AWS credentials
if ! aws s3 ls "s3://$S3_BUCKET" --region "$AWS_REGION" ${AWS_PROFILE:+--profile "$AWS_PROFILE"} &>/dev/null; then
    log_message "ERROR" "Cannot access S3 bucket '$S3_BUCKET'. Check AWS credentials and permissions."
    exit 1
fi

log_message "INFO" "AWS credentials verified. Checking for files to upload..."

# Array to track files for batch upload
declare -a files_to_upload

# ============================================================
# Process Screen Recordings
# ============================================================
if [ -d "$SCREEN_DIR" ]; then
    log_message "INFO" "Processing screen recordings..."
    
    while IFS= read -r file; do
        if upload_file_to_s3 "$file" "screen"; then
            ((++uploads_successful))
        else
            case $? in
                2) ((++uploads_skipped)) ;;
                *) ((++uploads_failed)) ;;
            esac
        fi
    done < <(find "$SCREEN_DIR" -maxdepth 1 -type f -name "*.mp4" | head -n "$UPLOAD_BATCH_SIZE")
fi

# ============================================================
# Process Microphone Recordings
# ============================================================
if [ "$VIDEOS_ONLY" != "true" ] && [ -d "$MIC_DIR" ]; then
    log_message "INFO" "Processing microphone recordings..."
    
    while IFS= read -r file; do
        if upload_file_to_s3 "$file" "microphone"; then
            ((++uploads_successful))
        else
            case $? in
                2) ((++uploads_skipped)) ;;
                *) ((++uploads_failed)) ;;
            esac
        fi
    done < <(find "$MIC_DIR" -maxdepth 1 -type f -name "*.wav" | head -n "$UPLOAD_BATCH_SIZE")
fi

# ============================================================
# Process System Audio Recordings
# ============================================================
if [ "$VIDEOS_ONLY" != "true" ] && [ -d "$SYSTEM_AUDIO_DIR" ]; then
    log_message "INFO" "Processing system audio recordings..."
    
    while IFS= read -r file; do
        if upload_file_to_s3 "$file" "system-audio"; then
            ((++uploads_successful))
        else
            case $? in
                2) ((++uploads_skipped)) ;;
                *) ((++uploads_failed)) ;;
            esac
        fi
    done < <(find "$SYSTEM_AUDIO_DIR" -maxdepth 1 -type f -name "*.wav" | head -n "$UPLOAD_BATCH_SIZE")
fi

# ============================================================
# Summary and Alerts
# ============================================================
log_message "INFO" "Upload session complete. Success: $uploads_successful, Skipped: $uploads_skipped, Failed: $uploads_failed"

# Send alert if there were failures
if [ "$uploads_failed" -gt 0 ] && [ -n "$ALERT_EMAIL" ]; then
    subject="Keeboarder Upload Alert - $uploads_failed files failed"
    message="$uploads_failed files failed to upload on $DEVICE_HOSTNAME at $(date)"
    echo "$message" | mail -s "$subject" "$ALERT_EMAIL"
    log_message "INFO" "Alert email sent to $ALERT_EMAIL"
fi

# Create marker file for monitoring
echo "$(date '+%Y-%m-%d %H:%M:%S') - Success: $uploads_successful, Skipped: $uploads_skipped, Failed: $uploads_failed" >> "$UPLOAD_MARKER_FILE"

# Keep only last 100 marker entries
tail -100 "$UPLOAD_MARKER_FILE" > "$UPLOAD_MARKER_FILE.tmp"
mv "$UPLOAD_MARKER_FILE.tmp" "$UPLOAD_MARKER_FILE"

if [ $uploads_failed -gt 0 ]; then
    exit 1
else
    exit 0
fi
