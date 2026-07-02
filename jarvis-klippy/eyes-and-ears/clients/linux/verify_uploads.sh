#!/bin/bash
set -uo pipefail

# Keeboarder Ubuntu GNOME S3 Verification Checker

CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$CONFIG_DIR/config.sh"

VERIFICATION_LOG="$LOGS_DIR/verification.log"
VERIFICATION_REPORT="$LOGS_DIR/verification_report.txt"
mkdir -p "$LOGS_DIR"

log_message() {
    local level="$1"
    local message="$2"
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$VERIFICATION_LOG"
}

file_exists_on_s3() {
    aws s3 ls "$1" --region "$AWS_REGION" ${AWS_PROFILE:+--profile "$AWS_PROFILE"} >/dev/null 2>&1
}

remote_file_exists() {
    local s3_path="$1"
    local key="${s3_path#s3://$S3_BUCKET/}"

    if [ "$UPLOAD_MODE" = "proxy" ]; then
        if ! proxy_metadata_request "$key" >/dev/null; then
            return 2
        fi
        case "${PROXY_METADATA_HTTP_CODE:-}" in
            200) return 0 ;;
            404) return 1 ;;
            *) return 2 ;;
        esac
    fi

    file_exists_on_s3 "$s3_path"
}

get_file_size() {
    stat -c%s "$1" 2>/dev/null
}

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

get_remote_file_size() {
    local s3_path="$1"
    local key="${s3_path#s3://$S3_BUCKET/}"

    if [ "$UPLOAD_MODE" = "proxy" ]; then
        if ! proxy_metadata_request "$key" "size" >/dev/null; then
            return 1
        fi
        if [ "${PROXY_METADATA_HTTP_CODE:-}" != "200" ]; then
            return 1
        fi
        printf '%s' "${PROXY_METADATA_SIZE:-}"
        return 0
    fi

    get_s3_file_size "$s3_path"
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
        --data "{\"username\":\"$(json_escape "$PROXY_USERNAME")\",\"password\":\"$(json_escape "$PROXY_PASSWORD")\"}" 2>>"$VERIFICATION_LOG")
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

proxy_metadata_request() {
    local key="$1"
    local output_field="${2:-exists}"
    local token response http_code body encoded_key

    PROXY_METADATA_HTTP_CODE=""
    PROXY_METADATA_SIZE=""

    if ! ensure_proxy_token; then
        return 1
    fi

    # shellcheck disable=SC1090
    source "$PROXY_TOKEN_FILE"
    encoded_key=$(url_encode_path "$key")

    log_message "INFO" "Proxy metadata sending (key=$key, url=$PROXY_URL/metadata/$encoded_key)"
    if ! response=$(curl -sS -w '\n%{http_code}' \
        -H "Authorization: Bearer $token" \
        -H "X-Client-Id: $PROXY_USERNAME" \
        "$PROXY_URL/metadata/$encoded_key" 2>>"$VERIFICATION_LOG"); then
        log_message "ERROR" "Proxy metadata request failed (key=$key)"
        return 1
    fi
    http_code=$(printf '%s\n' "$response" | tail -n 1)
    body=$(printf '%s\n' "$response" | sed '$d')
    log_message "INFO" "Proxy metadata response (key=$key, status=$http_code, body=$(compact_json_for_log "$body"))"

    if [ "$http_code" = "401" ]; then
        log_message "WARN" "Proxy metadata unauthorized; refreshing token and retrying once (key=$key)"
        if ! proxy_login "unauthorized"; then
            return 1
        fi

        # shellcheck disable=SC1090
        source "$PROXY_TOKEN_FILE"
        log_message "INFO" "Proxy metadata retry sending (key=$key, url=$PROXY_URL/metadata/$encoded_key)"
        if ! response=$(curl -sS -w '\n%{http_code}' \
            -H "Authorization: Bearer $token" \
            -H "X-Client-Id: $PROXY_USERNAME" \
            "$PROXY_URL/metadata/$encoded_key" 2>>"$VERIFICATION_LOG"); then
            log_message "ERROR" "Proxy metadata retry failed (key=$key)"
            return 1
        fi
        http_code=$(printf '%s\n' "$response" | tail -n 1)
        body=$(printf '%s\n' "$response" | sed '$d')
        log_message "INFO" "Proxy metadata retry response (key=$key, status=$http_code, body=$(compact_json_for_log "$body"))"
    fi

    PROXY_METADATA_HTTP_CODE="$http_code"
    if [ "$http_code" = "200" ] && [ "$output_field" = "size" ]; then
        PROXY_METADATA_SIZE=$(extract_json_number "$body" "size")
    fi

    return 0
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

local_count=0
verified_count=0
missing_count=0
size_mismatch_count=0
verification_error_count=0

log_message "INFO" "=== S3 Verification Check Started ==="
> "$VERIFICATION_REPORT"

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

if [ "$UPLOAD_MODE" != "proxy" ] && ! aws s3 ls "s3://$S3_BUCKET" --region "$AWS_REGION" ${AWS_PROFILE:+--profile "$AWS_PROFILE"} >/dev/null 2>&1; then
    log_message "ERROR" "Cannot access S3 bucket '$S3_BUCKET'. Check AWS credentials and permissions."
    exit 1
fi

verify_directory() {
    local local_dir="$1"
    local s3_subpath="$2"
    local file_type="$3"

    log_message "INFO" "Verifying $file_type recordings..."
    [ -d "$local_dir" ] || return

    while IFS= read -r file; do
        ((++local_count))

        local basename s3_full_path remote_status
        basename=$(basename "$file")
        s3_full_path="s3://$S3_BUCKET/${S3_PREFIX}${DEVICE_HOSTNAME}/${s3_subpath}/${basename}"

        if remote_file_exists "$s3_full_path"; then
            if [ "$VERIFY_FILE_SIZE" = "true" ]; then
                local local_size s3_size
                if ! local_size=$(get_file_size "$file"); then
                    ((++verification_error_count))
                    echo "ERROR: $basename (could not read local file size)" >> "$VERIFICATION_REPORT"
                    log_message "ERROR" "Could not read local file size for $basename"
                    continue
                fi
                if ! s3_size=$(get_remote_file_size "$s3_full_path"); then
                    ((++verification_error_count))
                    echo "ERROR: $basename (could not read remote file size)" >> "$VERIFICATION_REPORT"
                    log_message "ERROR" "Could not read remote file size for $basename"
                    continue
                fi

                if [ "$local_size" != "$s3_size" ]; then
                    ((++size_mismatch_count))
                    echo "SIZE_MISMATCH: $basename (local: $local_size bytes, S3: $s3_size bytes)" >> "$VERIFICATION_REPORT"
                    log_message "WARN" "Size mismatch: $basename (local: $local_size, S3: $s3_size)"
                    continue
                fi
            fi
            ((++verified_count))
        else
            remote_status=$?
            case "$remote_status" in
                1)
                    ((++missing_count))
                    echo "MISSING: $basename (not found on S3)" >> "$VERIFICATION_REPORT"
                    log_message "WARN" "Missing on S3: $basename"
                    ;;
                *)
                    ((++verification_error_count))
                    echo "ERROR: $basename (verification failed)" >> "$VERIFICATION_REPORT"
                    log_message "ERROR" "Could not verify remote state for $basename"
                    ;;
            esac
        fi
    done < <(find "$local_dir" -maxdepth 1 -type f | sort)
}

verify_directory "$SCREEN_DIR" "screen" "Screen Recording"
verify_directory "$MIC_DIR" "microphone" "Microphone Recording"
verify_directory "$SYSTEM_AUDIO_DIR" "system-audio" "System Audio Recording"

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
    echo "Verification errors: $verification_error_count"
    echo ""

    if [ "$missing_count" -gt 0 ] || [ "$size_mismatch_count" -gt 0 ] || [ "$verification_error_count" -gt 0 ]; then
        echo "ISSUES FOUND:"
        echo "=========================================="
        cat "$VERIFICATION_REPORT"
        echo ""
    fi

    echo "Report generated: $(date '+%Y-%m-%d %H:%M:%S')"
} > "$VERIFICATION_REPORT.tmp"

mv "$VERIFICATION_REPORT.tmp" "$VERIFICATION_REPORT"

log_message "INFO" "Total local files: $local_count"
log_message "INFO" "Verified on S3: $verified_count"
log_message "INFO" "Missing on S3: $missing_count"
log_message "INFO" "Size mismatches: $size_mismatch_count"
log_message "INFO" "Verification errors: $verification_error_count"

if [ "$missing_count" -gt 0 ] || [ "$size_mismatch_count" -gt 0 ] || [ "$verification_error_count" -gt 0 ]; then
    if [ -n "$ALERT_EMAIL" ]; then
        mail -s "Keeboarder Verification Alert - Issues Found on $DEVICE_HOSTNAME" "$ALERT_EMAIL" < "$VERIFICATION_REPORT"
        log_message "INFO" "Alert email sent to $ALERT_EMAIL"
    fi
    exit 1
fi

log_message "INFO" "All files verified successfully!"
