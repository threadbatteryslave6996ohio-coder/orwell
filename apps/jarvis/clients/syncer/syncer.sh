#!/bin/bash
set -uo pipefail

# Keeboarder syncer client.
#
# For every recording type the syncer:
#   1. Lists completed segments in chronological order.
#   2. Leaves the single most recent segment (the still-in-progress file) as is.
#   3. Merges every older segment of the same container into one file.
#   4. Uploads that merged file to the bucket proxy.
#
# If only one older segment exists it is uploaded directly (nothing to merge).
# If the only segment present is the most recent one, the type is skipped.

CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$CONFIG_DIR/config.sh"
: "${MIN_FILE_AGE:=5}"
: "${FILE_STABILITY_WAIT_SECONDS:=3}"
: "${DELETE_AFTER_UPLOAD:=true}"
: "${VALIDATE_MEDIA_BEFORE_UPLOAD:=true}"

SYNCER_LOG="$LOGS_DIR/syncer.log"
SYNC_LOCK_DIR="$LOGS_DIR/.syncer.lock"
mkdir -p "$LOGS_DIR" "$WORK_DIR"

log_message() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$SYNCER_LOG"
}

# ============================================================
# Single-instance lock
# ============================================================

release_lock() {
    rm -f "$SYNC_LOCK_DIR/pid"
    rmdir "$SYNC_LOCK_DIR" 2>/dev/null || true
}

acquire_lock() {
    local lock_pid=""

    if mkdir "$SYNC_LOCK_DIR" 2>/dev/null; then
        printf '%s\n' "$$" > "$SYNC_LOCK_DIR/pid"
        trap release_lock EXIT
        trap 'exit 130' INT TERM HUP
        return 0
    fi

    if [ -r "$SYNC_LOCK_DIR/pid" ]; then
        read -r lock_pid < "$SYNC_LOCK_DIR/pid" || true
    fi
    if [ -n "$lock_pid" ] && kill -0 "$lock_pid" 2>/dev/null; then
        log_message "INFO" "Another syncer run is already active (pid=$lock_pid); skipping"
        return 1
    fi

    log_message "WARN" "Removing stale syncer lock"
    rm -f "$SYNC_LOCK_DIR/pid"
    rmdir "$SYNC_LOCK_DIR" 2>/dev/null || return 1
    mkdir "$SYNC_LOCK_DIR" || return 1
    printf '%s\n' "$$" > "$SYNC_LOCK_DIR/pid"
    trap release_lock EXIT
    trap 'exit 130' INT TERM HUP
}

# ============================================================
# File helpers
# ============================================================

file_signature() {
    stat -c '%s:%Y' "$1" 2>/dev/null
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

# A completed segment must be closed, old enough, and stable in size.
segment_is_ready() {
    local file="$1"
    local before after mtime now age

    [ -f "$file" ] || return 1

    if file_is_open "$file"; then
        log_message "DEBUG" "Not ready (open): $(basename "$file")"
        return 1
    fi

    mtime=$(stat -c%Y "$file" 2>/dev/null) || return 1
    now=$(date +%s)
    age=$((now - mtime))
    if [ "$age" -lt "$MIN_FILE_AGE" ]; then
        log_message "DEBUG" "Not ready (too new, ${age}s): $(basename "$file")"
        return 1
    fi

    before=$(file_signature "$file") || return 1
    sleep "$FILE_STABILITY_WAIT_SECONDS"
    after=$(file_signature "$file") || return 1
    if [ "$before" != "$after" ]; then
        log_message "DEBUG" "Not ready (changing): $(basename "$file")"
        return 1
    fi

    if [ "$VALIDATE_MEDIA_BEFORE_UPLOAD" = "true" ] && command -v ffprobe >/dev/null 2>&1; then
        if ! ffprobe -v error -show_entries format=duration \
            -of default=noprint_wrappers=1:nokey=1 "$file" >/dev/null 2>&1; then
            log_message "WARN" "Skipping invalid media: $(basename "$file")"
            return 1
        fi
    fi

    return 0
}

# ============================================================
# Merge
# ============================================================

# Concatenate the given files (stream copy) into $2. Returns non-zero on failure.
merge_segments() {
    local out="$1"
    shift
    local listfile
    listfile="$WORK_DIR/.concat-$$-$RANDOM.txt"

    : > "$listfile"
    local f escaped
    for f in "$@"; do
        # ffmpeg concat demuxer: escape single quotes as '\''
        escaped="${f//\'/\'\\\'\'}"
        printf "file '%s'\n" "$escaped" >> "$listfile"
    done

    ffmpeg -y -hide_banner -loglevel error \
        -f concat -safe 0 -i "$listfile" \
        -c copy "$out" >>"$SYNCER_LOG" 2>&1
    local rc=$?
    rm -f "$listfile"
    return $rc
}

# ============================================================
# Proxy auth + upload (same flow as the recorder uploader)
# ============================================================

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

extract_json_string() {
    local json="$1" key="$2"
    printf '%s' "$json" | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" | head -n 1
}

compact_json_for_log() {
    printf '%s' "$1" | tr '\n' ' ' | sed 's/[[:space:]][[:space:]]*/ /g; s/^ //; s/ $//'
}

token=""

proxy_login() {
    local reason="${1:-initial}"
    local response http_code body new_token expires_at

    if [ -z "$PROXY_USERNAME" ] || [ -z "$PROXY_PASSWORD" ]; then
        log_message "ERROR" "PROXY_USERNAME/PROXY_PASSWORD are required"
        return 1
    fi

    log_message "INFO" "Proxy auth attempt (reason=$reason, user=$PROXY_USERNAME, url=$PROXY_URL/login)"
    response=$(curl -sS -w '\n%{http_code}' \
        -H "Content-Type: application/json" \
        -X POST "$PROXY_URL/login" \
        --data "{\"username\":\"$(json_escape "$PROXY_USERNAME")\",\"password\":\"$(json_escape "$PROXY_PASSWORD")\"}" 2>>"$SYNCER_LOG")
    http_code=$(printf '%s\n' "$response" | tail -n 1)
    body=$(printf '%s\n' "$response" | sed '$d')

    if [ "$http_code" != "200" ]; then
        log_message "ERROR" "Proxy auth failed (status=$http_code, body=$(compact_json_for_log "$body"))"
        return 1
    fi

    new_token=$(extract_json_string "$body" "token")
    expires_at=$(extract_json_string "$body" "expiresAt")
    if [ -z "$new_token" ]; then
        log_message "ERROR" "Proxy auth response did not include a token"
        return 1
    fi

    token="$new_token"
    {
        printf 'token=%s\n' "$token"
        printf 'expires_at=%s\n' "$expires_at"
        printf 'received_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } > "$PROXY_TOKEN_FILE"
    chmod 600 "$PROXY_TOKEN_FILE"
    log_message "INFO" "Proxy auth token saved (expiresAt=${expires_at:-unknown})"
}

ensure_proxy_token() {
    if [ -n "$token" ]; then
        return 0
    fi
    if [ -f "$PROXY_TOKEN_FILE" ]; then
        # shellcheck disable=SC1090
        source "$PROXY_TOKEN_FILE"
        if [ -n "${token:-}" ]; then
            return 0
        fi
    fi
    proxy_login "missing-token"
}

proxy_upload_request() {
    local file="$1" folder="$2" basename="$3"

    curl -sS -w '\n%{http_code}' \
        -H "Authorization: Bearer $token" \
        -H "X-Client-Id: $PROXY_USERNAME" \
        -F "file=@${file}" \
        -F "folder=${folder}" \
        -F "fileName=${basename}" \
        "$PROXY_URL/upload" 2>>"$SYNCER_LOG"
}

# upload_to_proxy <file> <folder> <basename>
upload_to_proxy() {
    local file="$1" folder="$2" basename="$3"
    local response http_code body

    if ! ensure_proxy_token; then
        return 1
    fi

    local size
    size=$(stat -c%s "$file" 2>/dev/null)
    log_message "INFO" "Proxy upload sending (file=$basename, size=$size, folder=$folder)"
    response=$(proxy_upload_request "$file" "$folder" "$basename")
    http_code=$(printf '%s\n' "$response" | tail -n 1)
    body=$(printf '%s\n' "$response" | sed '$d')
    log_message "INFO" "Proxy upload response (file=$basename, status=$http_code, body=$(compact_json_for_log "$body"))"

    if [ "$http_code" = "401" ]; then
        log_message "WARN" "Proxy upload unauthorized; refreshing token and retrying once ($basename)"
        if ! proxy_login "unauthorized"; then
            return 1
        fi
        response=$(proxy_upload_request "$file" "$folder" "$basename")
        http_code=$(printf '%s\n' "$response" | tail -n 1)
        body=$(printf '%s\n' "$response" | sed '$d')
        log_message "INFO" "Proxy upload retry response (file=$basename, status=$http_code, body=$(compact_json_for_log "$body"))"
    fi

    if [ "$http_code" = "200" ]; then
        log_message "INFO" "Successfully uploaded via proxy: $basename"
        return 0
    fi

    log_message "ERROR" "Proxy upload failed: $basename (status=$http_code)"
    return 1
}

# ============================================================
# Per-type synchronization
# ============================================================

synced=0
skipped=0
failed=0

# stem_of <path> -> filename without directory or extension
stem_of() {
    local base
    base=$(basename "$1")
    printf '%s' "${base%.*}"
}

# sync_type <dir> <extension> <folder-label>
sync_type() {
    local dir="$1" ext="$2" label="$3"
    local folder="${S3_PREFIX}${DEVICE_HOSTNAME}/${label}"

    [ -d "$dir" ] || return 0

    local files=()
    while IFS= read -r file; do
        files+=("$file")
    done < <(find "$dir" -maxdepth 1 -type f -name "*.$ext" 2>/dev/null | sort)

    local count=${#files[@]}
    if [ "$count" -le 1 ]; then
        # Only the in-progress segment (or nothing) — leave it as is.
        [ "$count" -eq 1 ] && log_message "DEBUG" "$label/$ext: only the current segment present; leaving as is"
        return 0
    fi

    # Everything except the most recent (last) segment is a merge candidate.
    local current="${files[$((count - 1))]}"
    log_message "INFO" "$label/$ext: leaving current segment as is: $(basename "$current")"

    local candidates=()
    local i
    for ((i = 0; i < count - 1; i++)); do
        if segment_is_ready "${files[$i]}"; then
            candidates+=("${files[$i]}")
        else
            ((++skipped))
        fi
    done

    local n=${#candidates[@]}
    if [ "$n" -eq 0 ]; then
        return 0
    fi

    local upload_path basename delete_sources="false"
    if [ "$n" -eq 1 ]; then
        # Nothing to merge — upload the single completed segment directly.
        upload_path="${candidates[0]}"
        basename=$(basename "$upload_path")
        log_message "INFO" "$label/$ext: 1 completed segment; uploading directly ($basename)"
    else
        # Merge all completed segments into one file named for their time range.
        local first_stem last_stem
        first_stem=$(stem_of "${candidates[0]}")
        last_stem=$(stem_of "${candidates[$((n - 1))]}")
        basename="${first_stem}__thru__${last_stem}.${ext}"
        upload_path="$WORK_DIR/$basename"

        log_message "INFO" "$label/$ext: merging $n segments into $basename"
        if ! merge_segments "$upload_path" "${candidates[@]}"; then
            log_message "ERROR" "$label/$ext: merge failed; leaving source segments in place"
            rm -f "$upload_path"
            ((++failed))
            return 0
        fi
        delete_sources="true"
    fi

    if upload_to_proxy "$upload_path" "$folder" "$basename"; then
        ((++synced))
        if [ "$DELETE_AFTER_UPLOAD" = "true" ]; then
            local c
            for c in "${candidates[@]}"; do
                rm -f "$c"
            done
            log_message "DEBUG" "$label/$ext: removed $n uploaded source segment(s)"
        fi
        # The merged scratch file is always disposable once uploaded.
        [ "$delete_sources" = "true" ] && rm -f "$upload_path"
    else
        ((++failed))
        # Keep the merged scratch file out of the recordings tree on failure too.
        [ "$delete_sources" = "true" ] && rm -f "$upload_path"
    fi
}

# ============================================================
# Main
# ============================================================

log_message "INFO" "=== Syncer started ==="

if ! command -v curl >/dev/null 2>&1; then
    log_message "ERROR" "curl not found. Install with: sudo apt install curl"
    exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
    log_message "ERROR" "ffmpeg not found. Install with: sudo apt install ffmpeg"
    exit 1
fi

if ! acquire_lock; then
    exit 0
fi

for ext in $SCREEN_EXTENSIONS; do
    sync_type "$SCREEN_DIR" "$ext" "screen"
done
for ext in $MIC_EXTENSIONS; do
    sync_type "$MIC_DIR" "$ext" "microphone"
done
for ext in $SYSTEM_AUDIO_EXTENSIONS; do
    sync_type "$SYSTEM_AUDIO_DIR" "$ext" "system-audio"
done

log_message "INFO" "Syncer complete. Synced: $synced, Skipped: $skipped, Failed: $failed"

[ "$failed" -eq 0 ]
