#!/bin/bash
set -uo pipefail

# Keeboarder Ubuntu GNOME S3 Uploader

CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$CONFIG_DIR/config.sh"
: "${FILE_STABILITY_WAIT_SECONDS:=3}"
: "${WAYLAND_SCREEN_EXTENSION:=webm}"

UPLOADER_LOG="$LOGS_DIR/uploader.log"
UPLOAD_MARKER_FILE="$LOGS_DIR/.upload_marker"
mkdir -p "$LOGS_DIR"

VIDEOS_ONLY="false"
if [ "${1:-}" = "--videos-only" ]; then
    VIDEOS_ONLY="true"
fi

log_message() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
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

upload_file_to_s3() {
    local file="$1"
    local s3_path="$2"
    local device_name="$DEVICE_HOSTNAME"

    [ -f "$file" ] || return 1

    if ! recording_is_complete "$file"; then
        log_message "DEBUG" "Skipping $file (still open or changing)"
        return 2
    fi

    local file_size initial_signature
    initial_signature=$(file_signature "$file") || return 2
    file_size=$(stat -c%s "$file" 2>/dev/null)

    if [ "$file_size" -lt 1024 ]; then
        log_message "DEBUG" "Skipping $file (too small: $file_size bytes)"
        return 2
    fi

    local file_mtime current_time file_age
    file_mtime=$(stat -c%Y "$file" 2>/dev/null)
    current_time=$(date +%s)
    file_age=$((current_time - file_mtime))

    if [ "$file_age" -lt "$MIN_FILE_AGE" ]; then
        log_message "DEBUG" "Skipping $file (too new: $file_age seconds old)"
        return 2
    fi

    if ! validate_media_file "$file"; then
        log_message "WARN" "Skipping $file (media validation failed; leaving local file in place)"
        return 2
    fi

    local basename s3_full_path
    basename=$(basename "$file")
    s3_full_path="s3://$S3_BUCKET/${S3_PREFIX}${device_name}/${s3_path}/${basename}"

    if [ "$s3_path" = "screen" ] && [ "$CHECK_REMOTE_BEFORE_UPLOAD" = "true" ]; then
        check_remote_video "$s3_full_path" "$file_size"
        local remote_status=$?
        case "$remote_status" in
            0)
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
                ;;
            1)
                log_message "INFO" "Remote video is missing or different; uploading: $basename"
                ;;
            *)
                log_message "ERROR" "Could not check remote state for $basename; leaving local file in place"
                return 1
                ;;
        esac
    fi

    if ! file_is_unchanged_and_closed "$file" "$initial_signature"; then
        log_message "DEBUG" "Skipping $file (opened or changed during synchronization check)"
        return 2
    fi

    if [ "$UPLOAD_MODE" = "proxy" ]; then
        upload_file_to_proxy "$file" "${S3_PREFIX}${device_name}/${s3_path}" "$basename" "$file_size" "$file_age"
        return $?
    fi

    log_message "INFO" "Uploading: $basename to $s3_full_path (${file_size} bytes, age ${file_age}s)"

    aws s3 cp "$file" "$s3_full_path" \
        --region "$AWS_REGION" \
        ${AWS_PROFILE:+--profile "$AWS_PROFILE"} \
        --metadata "uploaded=$(date -u +%Y-%m-%dT%H:%M:%SZ),device=$device_name,local_size=$file_size" \
        --sse AES256 \
        2>&1 | tee -a "$UPLOADER_LOG"

    if [ "${PIPESTATUS[0]}" -eq 0 ]; then
        log_message "INFO" "Successfully uploaded: $basename"
        if [ "$DELETE_AFTER_UPLOAD" = "true" ]; then
            rm -f "$file"
            log_message "DEBUG" "Deleted local file: $file"
        fi
        return 0
    fi

    log_message "ERROR" "Failed to upload: $basename"
    return 1
}

proxy_login() {
    local reason="${1:-initial}"
    local response http_code body token expires_at

    if [ -z "$PROXY_USERNAME" ] || [ -z "$PROXY_PASSWORD" ]; then
        log_message "ERROR" "Proxy username/password are required when UPLOAD_MODE=proxy"
        return 1
    fi

    log_message "INFO" "Proxy auth attempt started (reason=$reason, user=$PROXY_USERNAME, url=$PROXY_URL/login)"

    response=$(curl -sS -w '\n%{http_code}' \
        -H "Content-Type: application/json" \
        -X POST "$PROXY_URL/login" \
        --data "{\"username\":\"$(json_escape "$PROXY_USERNAME")\",\"password\":\"$(json_escape "$PROXY_PASSWORD")\"}" 2>>"$UPLOADER_LOG")
    http_code=$(printf '%s\n' "$response" | tail -n 1)
    body=$(printf '%s\n' "$response" | sed '$d')

    log_message "INFO" "Proxy auth response received (status=$http_code, user=$PROXY_USERNAME)"

    if [ "$http_code" != "200" ]; then
        log_message "ERROR" "Proxy auth failed (status=$http_code, body=$(compact_json_for_log "$body"))"
        return 1
    fi

    token=$(extract_json_string "$body" "token")
    expires_at=$(extract_json_string "$body" "expiresAt")
    if [ -z "$token" ]; then
        log_message "ERROR" "Proxy auth response did not include a token"
        return 1
    fi

    {
        printf 'token=%s\n' "$token"
        printf 'expires_at=%s\n' "$expires_at"
        printf 'received_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } > "$PROXY_TOKEN_FILE"
    chmod 600 "$PROXY_TOKEN_FILE"

    log_message "INFO" "Proxy auth token saved (file=$PROXY_TOKEN_FILE, expiresAt=${expires_at:-unknown})"
}

ensure_proxy_token() {
    if [ -f "$PROXY_TOKEN_FILE" ]; then
        # shellcheck disable=SC1090
        source "$PROXY_TOKEN_FILE"
        if [ -n "${token:-}" ]; then
            return 0
        fi
    fi

    proxy_login "missing-token"
}

upload_file_to_proxy() {
    local file="$1"
    local folder="$2"
    local basename="$3"
    local file_size="$4"
    local file_age="$5"

    local token response http_code body
    if ! ensure_proxy_token; then
        return 1
    fi

    # shellcheck disable=SC1090
    source "$PROXY_TOKEN_FILE"

    log_message "INFO" "Proxy upload sending (file=$basename, size=$file_size, age=${file_age}s, folder=$folder, url=$PROXY_URL/upload)"
    response=$(proxy_upload_request "$file" "$folder" "$basename" "$token")
    http_code=$(printf '%s\n' "$response" | tail -n 1)
    body=$(printf '%s\n' "$response" | sed '$d')
    log_message "INFO" "Proxy upload response (file=$basename, status=$http_code, body=$(compact_json_for_log "$body"))"

    if [ "$http_code" = "401" ]; then
        log_message "WARN" "Proxy upload unauthorized; refreshing token and retrying once (file=$basename)"
        if ! proxy_login "unauthorized"; then
            return 1
        fi

        # shellcheck disable=SC1090
        source "$PROXY_TOKEN_FILE"
        log_message "INFO" "Proxy upload retry sending (file=$basename, folder=$folder, url=$PROXY_URL/upload)"
        response=$(proxy_upload_request "$file" "$folder" "$basename" "$token")
        http_code=$(printf '%s\n' "$response" | tail -n 1)
        body=$(printf '%s\n' "$response" | sed '$d')
        log_message "INFO" "Proxy upload retry response (file=$basename, status=$http_code, body=$(compact_json_for_log "$body"))"
    fi

    if [ "$http_code" = "200" ]; then
        log_message "INFO" "Successfully uploaded via proxy: $basename"
        if [ "$DELETE_AFTER_UPLOAD" = "true" ]; then
            rm -f "$file"
            log_message "DEBUG" "Deleted local file: $file"
        fi
        return 0
    fi

    log_message "ERROR" "Proxy upload failed: $basename (status=$http_code)"
    return 1
}

proxy_upload_request() {
    local file="$1"
    local folder="$2"
    local basename="$3"
    local token="$4"

    curl -sS -w '\n%{http_code}' \
        -H "Authorization: Bearer $token" \
        -H "X-Client-Id: $PROXY_USERNAME" \
        -F "file=@${file}" \
        -F "folder=${folder}" \
        -F "fileName=${basename}" \
        "$PROXY_URL/upload" 2>>"$UPLOADER_LOG"
}

check_remote_video() {
    local s3_path="$1"
    local local_size="$2"
    local key="${s3_path#s3://$S3_BUCKET/}"

    if [ "$UPLOAD_MODE" = "proxy" ]; then
        check_proxy_video "$key" "$local_size"
        return $?
    fi

    local remote_size
    if remote_size=$(aws s3api head-object \
        --bucket "$S3_BUCKET" \
        --key "$key" \
        --region "$AWS_REGION" \
        ${AWS_PROFILE:+--profile "$AWS_PROFILE"} \
        --query 'ContentLength' \
        --output text 2>>"$UPLOADER_LOG"); then
        [ "$remote_size" = "$local_size" ] && return 0
        return 1
    fi

    # Bucket connectivity is checked before processing. A failed HEAD here is
    # therefore treated as an absent object and uploaded again.
    return 1
}

check_proxy_video() {
    local key="$1"
    local local_size="$2"
    local token response http_code body remote_size encoded_key

    if ! ensure_proxy_token; then
        return 2
    fi

    # shellcheck disable=SC1090
    source "$PROXY_TOKEN_FILE"
    encoded_key=$(url_encode_path "$key")
    response=$(proxy_metadata_request "$encoded_key" "$token")
    http_code=$(printf '%s\n' "$response" | tail -n 1)
    body=$(printf '%s\n' "$response" | sed '$d')
    log_message "INFO" "Proxy sync check response (key=$key, status=$http_code, body=$(compact_json_for_log "$body"))"

    if [ "$http_code" = "401" ]; then
        if ! proxy_login "sync-check-unauthorized"; then
            return 2
        fi
        # shellcheck disable=SC1090
        source "$PROXY_TOKEN_FILE"
        response=$(proxy_metadata_request "$encoded_key" "$token")
        http_code=$(printf '%s\n' "$response" | tail -n 1)
        body=$(printf '%s\n' "$response" | sed '$d')
    fi

    if [ "$http_code" = "404" ]; then
        return 1
    fi
    if [ "$http_code" != "200" ]; then
        return 2
    fi

    remote_size=$(extract_json_number "$body" "size")
    [ -n "$remote_size" ] || return 2
    [ "$remote_size" = "$local_size" ] && return 0
    return 1
}

proxy_metadata_request() {
    local encoded_key="$1"
    local token="$2"

    curl -sS -w '\n%{http_code}' \
        -H "Authorization: Bearer $token" \
        -H "X-Client-Id: $PROXY_USERNAME" \
        "$PROXY_URL/metadata/$encoded_key" 2>>"$UPLOADER_LOG"
}

validate_media_file() {
    local file="$1"

    [ "$VALIDATE_MEDIA_BEFORE_UPLOAD" = "true" ] || return 0

    if ! command -v ffprobe >/dev/null 2>&1; then
        log_message "WARN" "ffprobe not found; cannot validate media before upload"
        return 1
    fi

    ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$file" >/dev/null 2>&1
}

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

extract_json_string() {
    local json="$1"
    local key="$2"
    printf '%s' "$json" | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" | head -n 1
}

extract_json_number() {
    local json="$1"
    local key="$2"
    printf '%s' "$json" | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p" | head -n 1
}

compact_json_for_log() {
    printf '%s' "$1" | tr '\n' ' ' | sed 's/[[:space:]][[:space:]]*/ /g; s/^ //; s/ $//'
}

url_encode_path() {
    local value="$1"
    local length=${#value}
    local i char encoded=""

    for ((i = 0; i < length; i++)); do
        char="${value:i:1}"
        case "$char" in
            [a-zA-Z0-9.~_/-]) encoded+="$char" ;;
            *) printf -v encoded '%s%%%02X' "$encoded" "'$char" ;;
        esac
    done

    printf '%s' "$encoded"
}

uploads_successful=0
uploads_failed=0
uploads_skipped=0
UPLOAD_LOCK_DIR="$LOGS_DIR/.upload-sync.lock"

log_message "INFO" "=== S3 Uploader Started ==="

if ! acquire_upload_lock; then
    exit 0
fi

if [ "$UPLOAD_MODE" = "proxy" ]; then
    if ! command -v curl >/dev/null 2>&1; then
        log_message "ERROR" "curl not found. Install with: sudo apt install curl"
        exit 1
    fi
else
    if ! command -v aws >/dev/null 2>&1; then
        log_message "ERROR" "AWS CLI not found. Install with: sudo apt install awscli"
        exit 1
    fi
fi

if [ "$VALIDATE_MEDIA_BEFORE_UPLOAD" = "true" ] && ! command -v ffprobe >/dev/null 2>&1; then
    log_message "ERROR" "ffprobe not found. Install with: sudo apt install ffmpeg"
    exit 1
fi

if [ "$UPLOAD_MODE" != "proxy" ] && ! aws s3 ls "s3://$S3_BUCKET" --region "$AWS_REGION" ${AWS_PROFILE:+--profile "$AWS_PROFILE"} >/dev/null 2>&1; then
    log_message "ERROR" "Cannot access S3 bucket '$S3_BUCKET'. Check AWS credentials and permissions."
    exit 1
fi

process_directory() {
    local dir="$1"
    local pattern="$2"
    local s3_path="$3"
    local label="$4"

    [ -d "$dir" ] || return
    log_message "INFO" "Processing $label recordings..."

    while IFS= read -r file; do
        if upload_file_to_s3 "$file" "$s3_path"; then
            ((++uploads_successful))
        else
            case $? in
                2)
                    ((++uploads_skipped))
                    ;;
                *)
                    ((++uploads_failed))
                    ;;
            esac
        fi
    done < <(find "$dir" -maxdepth 1 -type f -name "$pattern" | sort | head -n "$UPLOAD_BATCH_SIZE")
}

process_directory "$SCREEN_DIR" "*.$SCREEN_SEGMENT_EXTENSION" "screen" "screen"
if [ "$WAYLAND_SCREEN_EXTENSION" != "$SCREEN_SEGMENT_EXTENSION" ]; then
    process_directory "$SCREEN_DIR" "*.$WAYLAND_SCREEN_EXTENSION" "screen" "GNOME Wayland screen"
fi
if [ "$VIDEOS_ONLY" != "true" ]; then
    process_directory "$MIC_DIR" "*.wav" "microphone" "microphone"
    process_directory "$SYSTEM_AUDIO_DIR" "*.wav" "system-audio" "system audio"
fi

log_message "INFO" "Upload session complete. Success: $uploads_successful, Skipped: $uploads_skipped, Failed: $uploads_failed"

if [ "$uploads_failed" -gt 0 ] && [ -n "$ALERT_EMAIL" ]; then
    echo "$uploads_failed files failed to upload on $DEVICE_HOSTNAME at $(date)" | \
        mail -s "Keeboarder Upload Alert - $uploads_failed files failed" "$ALERT_EMAIL"
    log_message "INFO" "Alert email sent to $ALERT_EMAIL"
fi

echo "$(date '+%Y-%m-%d %H:%M:%S') - Success: $uploads_successful, Skipped: $uploads_skipped, Failed: $uploads_failed" >> "$UPLOAD_MARKER_FILE"
tail -100 "$UPLOAD_MARKER_FILE" > "$UPLOAD_MARKER_FILE.tmp"
mv "$UPLOAD_MARKER_FILE.tmp" "$UPLOAD_MARKER_FILE"

[ "$uploads_failed" -eq 0 ]
