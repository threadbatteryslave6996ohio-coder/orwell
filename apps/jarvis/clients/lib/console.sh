#!/bin/bash
# Terminal output and the housekeeping shared by the mac and linux management scripts.
#
# Sourced, never executed. Nothing here knows how a recorder is started or stopped — that is
# launchctl on one platform and systemctl --user on the other, and it stays in each manage.sh.
# What is here is everything those two scripts had written out twice: the colour constants, the
# print helpers, and the retention sweep over the recording directories.
#
# Bash 3.2 compatible; macOS ships that as /bin/bash.

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

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

# Deletes recordings older than N days from the three capture directories.
#
# Only the top level of each is swept (`-maxdepth 1`): the recorders write flat, and a user who has
# filed something into a subdirectory has said it is worth keeping. Uploaded state is not consulted
# — this is a local disk sweep, and the syncer client is what decides a file has reached the bucket.
cleanup_old_files() {
    local days="${1:-7}"
    print_header "Cleaning Local Files Older Than $days Days"

    local file_count=0
    local dir
    local file
    for dir in "$SCREEN_DIR" "$MIC_DIR" "$SYSTEM_AUDIO_DIR"; do
        [ -d "$dir" ] || continue
        while IFS= read -r file; do
            print_info "Removing: $(basename "$file")"
            rm -f "$file"
            file_count=$((file_count + 1))
        done < <(find "$dir" -maxdepth 1 -type f -mtime +"$days")
    done

    print_success "Removed $file_count old files"
}

# Per-directory sizes, for a `status` command. Silent when nothing has been recorded yet.
print_disk_usage() {
    print_section "Disk Usage"
    if [ ! -d "$RECORDINGS_DIR" ]; then
        print_info "No recordings directory yet: $RECORDINGS_DIR"
        return
    fi

    print_info "Path: $RECORDINGS_DIR"
    print_info "Total: $(du -sh "$RECORDINGS_DIR" 2>/dev/null | cut -f1)"

    local dir
    local label
    for pair in "$SCREEN_DIR:Screen recordings" "$MIC_DIR:Microphone recordings" \
                "$SYSTEM_AUDIO_DIR:System audio"; do
        dir="${pair%%:*}"
        label="${pair##*:}"
        if [ -d "$dir" ]; then
            print_info "  $label: $(du -sh "$dir" 2>/dev/null | cut -f1)"
        fi
    done
}

# The recorder log, which both platforms write to the same place. $1 is how many lines.
print_recorder_log() {
    local lines="${1:-20}"
    print_section "Recorder log (last $lines lines)"
    if [ -f "$LOGS_DIR/recorder.log" ]; then
        tail -"$lines" "$LOGS_DIR/recorder.log"
    else
        print_error "No recorder log yet: $LOGS_DIR/recorder.log"
    fi
}
