#!/bin/bash

# Keeboarder macOS - Management Script
# Comprehensive tool for managing recorder, uploads, and verification

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/config.sh"

# ============================================================
# Functions
# ============================================================

print_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

print_section() {
    echo -e "${YELLOW}$1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo "  $1"
}

# Start all services
start_all() {
    print_header "Starting Keeboarder Services"
    
    print_section "Starting recorder..."
    launchctl load ~/Library/LaunchAgents/com.keeboarder.recorder.plist 2>/dev/null || true
    sleep 2
    
    if ps aux | grep -q "[s]tart_recorder"; then
        print_success "Recorder is running"
    else
        print_error "Recorder failed to start"
    fi
    
    print_section "Starting cron jobs..."
    crontab -l | grep -q "upload_to_s3" && print_success "Upload job is configured" || print_error "Upload job not found"
    crontab -l | grep -q "verify_uploads" && print_success "Verification job is configured" || print_error "Verification job not found"
}

# Stop all services
stop_all() {
    print_header "Stopping Keeboarder Services"
    
    print_section "Stopping recorder..."
    launchctl unload ~/Library/LaunchAgents/com.keeboarder.recorder.plist 2>/dev/null || true
    pkill -f start_recorder.sh || true
    pkill -f ffmpeg || true
    sleep 1
    
    if ! ps aux | grep -q "[s]tart_recorder"; then
        print_success "Recorder stopped"
    else
        print_error "Recorder still running"
    fi
    
    print_info "Cron jobs remain active (use 'crontab -e' to disable)"
}

# Restart all services
restart_all() {
    print_header "Restarting Keeboarder Services"
    
    stop_all
    sleep 2
    start_all
}

# Show status
show_status() {
    print_header "Keeboarder System Status"
    
    print_section "Recorder Status"
    if ps aux | grep -q "[f]fmpeg"; then
        print_success "FFmpeg is running"
        ps aux | grep "[f]fmpeg" | awk '{print "  PID " $2}'
    else
        print_error "FFmpeg is not running"
    fi
    
    if launchctl list | grep -q "com.keeboarder.recorder"; then
        print_success "LaunchAgent is loaded"
    else
        print_error "LaunchAgent is not loaded"
    fi
    
    print_section "Disk Usage"
    if [ -d "$RECORDINGS_DIR" ]; then
        local size=$(du -sh "$RECORDINGS_DIR" | cut -f1)
        print_info "Recording directory: $size"
        print_info "Path: $RECORDINGS_DIR"
        
        if [ -d "$SCREEN_DIR" ]; then
            local screen_size=$(du -sh "$SCREEN_DIR" | cut -f1)
            print_info "  Screen recordings: $screen_size"
        fi
        
        if [ -d "$MIC_DIR" ]; then
            local mic_size=$(du -sh "$MIC_DIR" | cut -f1)
            print_info "  Microphone recordings: $mic_size"
        fi
        
        if [ -d "$SYSTEM_AUDIO_DIR" ]; then
            local audio_size=$(du -sh "$SYSTEM_AUDIO_DIR" | cut -f1)
            print_info "  System audio: $audio_size"
        fi
    fi
    
    print_section "Cron Jobs"
    if crontab -l 2>/dev/null | grep -q "upload_to_s3"; then
        print_success "Upload job configured"
        print_info "Pattern: $UPLOAD_CRON"
    else
        print_error "Upload job not configured"
    fi
    
    if crontab -l 2>/dev/null | grep -q "verify_uploads"; then
        print_success "Verification job configured"
        print_info "Pattern: $VERIFY_CRON"
    else
        print_error "Verification job not configured"
    fi
    
    print_section "AWS Configuration"
    if aws sts get-caller-identity &>/dev/null; then
        print_success "AWS credentials valid"
        local identity=$(aws sts get-caller-identity --query 'Account' --output text)
        print_info "Account: $identity"
    else
        print_error "AWS credentials invalid or not configured"
    fi
    
    if aws s3 ls "s3://$S3_BUCKET" --region "$AWS_REGION" &>/dev/null; then
        print_success "S3 bucket accessible"
        print_info "Bucket: $S3_BUCKET"
        print_info "Region: $AWS_REGION"
        local remote_size=$(aws s3 ls "s3://$S3_BUCKET" --recursive --summarize --region "$AWS_REGION" | grep "Total Size" | awk '{print $3}')
        if [ -n "$remote_size" ]; then
            print_info "Remote size: $(numfmt --to=iec-i --suffix=B "$remote_size" 2>/dev/null || echo "$remote_size bytes")"
        fi
    else
        print_error "S3 bucket not accessible"
    fi
}

# Show logs
show_logs() {
    print_header "Recent Logs"
    
    if [ -n "$1" ] && [ "$1" != "all" ]; then
        local log_type="$1"
    else
        local log_type="all"
    fi
    
    case "$log_type" in
        recorder)
            print_section "Recorder Log:"
            if [ -f "$LOGS_DIR/recorder.log" ]; then
                tail -50 "$LOGS_DIR/recorder.log"
            else
                print_error "Log file not found"
            fi
            ;;
        uploader)
            print_section "Uploader Log:"
            if [ -f "$LOGS_DIR/uploader.log" ]; then
                tail -50 "$LOGS_DIR/uploader.log"
            else
                print_error "Log file not found"
            fi
            ;;
        verification)
            print_section "Verification Log:"
            if [ -f "$LOGS_DIR/verification.log" ]; then
                tail -50 "$LOGS_DIR/verification.log"
            else
                print_error "Log file not found"
            fi
            ;;
        *)
            print_section "Last 20 Recorder Entries:"
            if [ -f "$LOGS_DIR/recorder.log" ]; then
                tail -20 "$LOGS_DIR/recorder.log"
            else
                print_error "Log file not found"
            fi
            
            echo ""
            print_section "Last 10 Uploader Entries:"
            if [ -f "$LOGS_DIR/uploader.log" ]; then
                tail -10 "$LOGS_DIR/uploader.log"
            else
                print_info "No uploader log yet"
            fi
            
            echo ""
            print_section "Last 10 Verification Entries:"
            if [ -f "$LOGS_DIR/verification.log" ]; then
                tail -10 "$LOGS_DIR/verification.log"
            else
                print_info "No verification log yet"
            fi
            ;;
    esac
}

# Run manual upload
manual_upload() {
    print_header "Running Manual S3 Upload"
    
    if [ -x "$SCRIPTS_DIR/upload_to_s3.sh" ]; then
        "$SCRIPTS_DIR/upload_to_s3.sh"
        print_success "Upload completed"
    else
        print_error "Upload script not found or not executable"
    fi
}

manual_sync() {
    print_header "Synchronizing Videos"

    if [ -x "$SCRIPTS_DIR/sync_videos.sh" ]; then
        "$SCRIPTS_DIR/sync_videos.sh"
        print_success "Video synchronization completed"
    else
        print_error "Video sync script not found or not executable"
    fi
}

# Run manual verification
manual_verify() {
    print_header "Running Manual S3 Verification"
    
    if [ -x "$SCRIPTS_DIR/verify_uploads.sh" ]; then
        "$SCRIPTS_DIR/verify_uploads.sh"
        print_success "Verification completed"
    else
        print_error "Verification script not found or not executable"
    fi
}

# Clean up old files
cleanup_old_files() {
    print_header "Cleaning Up Old Local Files"
    
    if [ -z "$1" ]; then
        local days=7
    else
        local days="$1"
    fi
    
    print_section "Removing files older than $days days..."
    
    local file_count=0
    
    for dir in "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR"; do
        if [ -d "$dir" ]; then
            while IFS= read -r file; do
                print_info "Removing: $(basename "$file")"
                rm -f "$file"
                ((++file_count))
            done < <(find "$dir" -maxdepth 1 -type f -mtime +$days)
        fi
    done
    
    print_success "Removed $file_count old files"
}

# Show detailed status
full_status() {
    print_header "Keeboarder Full System Report"
    
    print_section "Configuration"
    print_info "S3 Bucket: $S3_BUCKET"
    print_info "AWS Region: $AWS_REGION"
    print_info "Device: $DEVICE_HOSTNAME"
    print_info "Screen Device: $SCREEN_DEVICE"
    print_info "Microphone Device: $MIC_DEVICE"
    print_info "System Audio Device: $SYSTEM_AUDIO_DEVICE"
    
    echo ""
    show_status
}

# ============================================================
# Main Menu
# ============================================================

print_usage() {
    cat << EOF
${BLUE}Keeboarder macOS Management Tool${NC}

${YELLOW}Usage:${NC}
  ./manage.sh [command] [options]

${YELLOW}Commands:${NC}
  start          Start all services (recorder, cron)
  stop           Stop all services
  restart        Restart all services
  status         Show current status
  full-status    Show detailed system report
  logs           Show recent logs
  logs recorder  Show recorder logs
  logs uploader  Show uploader logs
  logs verify    Show verification logs
  upload         Run manual S3 upload
  sync           Check and synchronize videos with S3
  verify         Run manual S3 verification
  cleanup [days] Remove local files older than N days (default: 7)
  help           Show this help message

${YELLOW}Examples:${NC}
  ./manage.sh status                 # Check current status
  ./manage.sh logs recorder          # View recorder logs
  ./manage.sh upload                 # Upload files manually
  ./manage.sh verify                 # Verify uploaded files
  ./manage.sh cleanup 30             # Remove files older than 30 days

EOF
}

# Main dispatch
if [ $# -eq 0 ]; then
    print_usage
    show_status
    exit 0
fi

case "$1" in
    start)
        start_all
        ;;
    stop)
        stop_all
        ;;
    restart)
        restart_all
        ;;
    status)
        show_status
        ;;
    full-status)
        full_status
        ;;
    logs)
        show_logs "$2"
        ;;
    upload)
        manual_upload
        ;;
    sync)
        manual_sync
        ;;
    verify|verification|check)
        manual_verify
        ;;
    cleanup)
        cleanup_old_files "$2"
        ;;
    help|--help|-h)
        print_usage
        ;;
    *)
        print_error "Unknown command: $1"
        echo ""
        print_usage
        exit 1
        ;;
esac

echo ""
