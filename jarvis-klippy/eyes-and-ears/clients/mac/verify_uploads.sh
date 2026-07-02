#!/bin/bash
set -uo pipefail

# Keeboarder macOS S3 Verification Checker
# Verifies that all local recording files exist on S3
# Runs periodically via cron (see config.sh for frequency)

# Source configuration
CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$CONFIG_DIR/config.sh"

# Initialize logging
VERIFICATION_LOG="$LOGS_DIR/verification.log"
VERIFICATION_REPORT="$LOGS_DIR/verification_report.txt"

# Create directories if they don't exist
mkdir -p "$LOGS_DIR"

# Logging function
log_message() {
    local level="$1"
    local message="$2"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$VERIFICATION_LOG"
}

# Function to check if file exists on S3
file_exists_on_s3() {
    local s3_path="$1"
    
    aws s3 ls "$s3_path" \
        --region "$AWS_REGION" \
        ${AWS_PROFILE:+--profile "$AWS_PROFILE"} \
        &>/dev/null
    
    return $?
}

# Function to get file size
get_file_size() {
    local filepath="$1"
    stat -f%z "$filepath" 2>/dev/null || stat -c%s "$filepath" 2>/dev/null
}

# Function to get S3 file size
get_s3_file_size() {
    local s3_path="$1"
    
    aws s3api head-object \
        --bucket "$S3_BUCKET" \
        --key "${s3_path#s3://$S3_BUCKET/}" \
        --region "$AWS_REGION" \
        ${AWS_PROFILE:+--profile "$AWS_PROFILE"} \
        --query 'ContentLength' \
        --output text 2>/dev/null
}

# Initialize counters
local_count=0
s3_count=0
verified_count=0
missing_count=0
size_mismatch_count=0

log_message "INFO" "=== S3 Verification Check Started ==="
log_message "DEBUG" "Device: $DEVICE_HOSTNAME"
log_message "DEBUG" "S3 Bucket: $S3_BUCKET"

# Clear previous report
> "$VERIFICATION_REPORT"

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

log_message "INFO" "AWS credentials verified. Starting verification..."

# ============================================================
# Verify Screen Recordings
# ============================================================
verify_directory() {
    local local_dir="$1"
    local s3_subpath="$2"
    local file_type="$3"
    
    log_message "INFO" "Verifying $file_type recordings..."
    
    if [ ! -d "$local_dir" ]; then
        log_message "WARN" "Directory not found: $local_dir"
        return
    fi
    
    local dir_local_count=0
    local dir_verified_count=0
    local dir_missing_count=0
    local dir_mismatch_count=0
    
    while IFS= read -r file; do
        ((++local_count))
        ((++dir_local_count))
        
        local basename=$(basename "$file")
        local s3_full_path="s3://$S3_BUCKET/${S3_PREFIX}${DEVICE_HOSTNAME}/${s3_subpath}/${basename}"
        
        # Check if file exists on S3
        if file_exists_on_s3 "$s3_full_path"; then
            ((++verified_count))
            ((++dir_verified_count))
            
            # Optionally verify file size matches
            if [ "$VERIFY_FILE_SIZE" = "true" ]; then
                local local_size=$(get_file_size "$file")
                local s3_size=$(get_s3_file_size "$s3_full_path")
                
                if [ "$local_size" != "$s3_size" ]; then
                    ((++size_mismatch_count))
                    ((++dir_mismatch_count))
                    echo "SIZE_MISMATCH: $basename (local: $local_size bytes, S3: $s3_size bytes)" >> "$VERIFICATION_REPORT"
                    log_message "WARN" "Size mismatch: $basename (local: $local_size, S3: $s3_size)"
                else
                    log_message "DEBUG" "Verified: $basename ($local_size bytes)"
                fi
            else
                log_message "DEBUG" "Verified: $basename"
            fi
        else
            ((++missing_count))
            ((++dir_missing_count))
            echo "MISSING: $basename (not found on S3)" >> "$VERIFICATION_REPORT"
            log_message "WARN" "Missing on S3: $basename"
        fi
    done < <(find "$local_dir" -maxdepth 1 -type f)
    
    if [ $dir_local_count -gt 0 ]; then
        log_message "INFO" "$file_type - Local: $dir_local_count, Verified: $dir_verified_count, Missing: $dir_missing_count, Size Mismatch: $dir_mismatch_count"
    fi
}

verify_directory "$SCREEN_DIR" "screen" "Screen Recording"
verify_directory "$MIC_DIR" "microphone" "Microphone Recording"
verify_directory "$SYSTEM_AUDIO_DIR" "system-audio" "System Audio Recording"

# ============================================================
# Generate Summary Report
# ============================================================

log_message "INFO" "=== Verification Summary ==="
log_message "INFO" "Total local files: $local_count"
log_message "INFO" "Verified on S3: $verified_count"
log_message "INFO" "Missing on S3: $missing_count"
log_message "INFO" "Size mismatches: $size_mismatch_count"

# Write summary to report
{
    echo "=========================================="
    echo "Keeboarder S3 Verification Report"
    echo "Generated: $(date)"
    echo "Device: $DEVICE_HOSTNAME"
    echo "=========================================="
    echo ""
    echo "SUMMARY:"
    echo "Total local files: $local_count"
    echo "Verified on S3: $verified_count"
    echo "Missing on S3: $missing_count"
    echo "Size mismatches: $size_mismatch_count"
    echo ""
    
    if [ $missing_count -gt 0 ] || [ $size_mismatch_count -gt 0 ]; then
        echo "ISSUES FOUND:"
        echo "=========================================="
        cat "$VERIFICATION_REPORT"
        echo ""
    fi
    
    echo "Report generated: $(date '+%Y-%m-%d %H:%M:%S')"
} > "$VERIFICATION_REPORT.tmp"

mv "$VERIFICATION_REPORT.tmp" "$VERIFICATION_REPORT"

# ============================================================
# Send Alert if Issues Found
# ============================================================

if [ $missing_count -gt 0 ] || [ $size_mismatch_count -gt 0 ]; then
    log_message "WARN" "Issues detected: $missing_count missing, $size_mismatch_count size mismatches"
    
    if [ -n "$ALERT_EMAIL" ]; then
        subject="Keeboarder Verification Alert - Issues Found on $DEVICE_HOSTNAME"
        {
            echo "Issues found during verification:"
            echo ""
            echo "Missing files on S3: $missing_count"
            echo "Size mismatches: $size_mismatch_count"
            echo ""
            echo "See full report at: $VERIFICATION_REPORT"
            echo ""
            echo "Time: $(date)"
        } | mail -s "$subject" "$ALERT_EMAIL"
        
        log_message "INFO" "Alert email sent to $ALERT_EMAIL"
    fi
    
    exit 1
else
    log_message "INFO" "All files verified successfully!"
    exit 0
fi
