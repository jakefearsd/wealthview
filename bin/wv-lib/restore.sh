# shellcheck shell=bash
# restore.sh — restore a backup into the running stack.

wv_restore() {
    local file="" dry_run=0
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv restore <file> — restore a pg_dump backup into the running database.

Stops the app, terminates lingering connections, runs pg_restore --clean
--if-exists, then restarts the app. Files ending in .age are decrypted
on-the-fly using BACKUP_ENCRYPTION_KEY_FILE (path to an age identity file).

Prompts for confirmation before mutating data; set WV_ASSUME_YES=1 to skip.

OPTIONS
    --dry-run     print actions without executing them
USAGE
                return 0
                ;;
            --dry-run) dry_run=1; shift ;;
            -*) wv_die "Unknown option for 'restore': $1" ;;
            *)
                if [[ -n "$file" ]]; then
                    wv_die "restore takes exactly one file argument; got extra: $1"
                fi
                file="$1"
                shift
                ;;
        esac
    done

    if [[ -z "$file" ]]; then
        wv_err "Usage: ./wv restore <file>"
        wv_log "Available backups:"
        wv_backups || true
        return 2
    fi

    [[ -f "$file" ]] || wv_die "File not found: $file"

    wv_log "About to restore: $file"
    wv_warn "This REPLACES all data in the wealthview database."
    wv_confirm "Continue?" || {
        wv_log "Aborted."
        return 1
    }

    if (( dry_run == 1 )); then
        wv_log "[dry-run] Would stop app, restore $file, restart app."
        return 0
    fi

    local tmp_plain=""
    local feed_path="$file"
    if [[ "$file" == *.age ]]; then
        wv_require age
        local key_file="${BACKUP_ENCRYPTION_KEY_FILE:-$(wv_env_get BACKUP_ENCRYPTION_KEY_FILE)}"
        if [[ -z "$key_file" || ! -f "$key_file" ]]; then
            wv_die "Encrypted backup requires BACKUP_ENCRYPTION_KEY_FILE pointing to an age identity file."
        fi
        tmp_plain="$(mktemp -t wv-restore-XXXXXX.dump)"
        # Make sure we always clean up the plaintext copy on exit.
        # shellcheck disable=SC2064  # expand tmp_plain now, on purpose
        trap "rm -f '$tmp_plain'" EXIT
        wv_log "Decrypting $file -> temporary file"
        age -d -i "$key_file" -o "$tmp_plain" "$file" \
            || wv_die "age decryption failed"
        feed_path="$tmp_plain"
    fi

    wv_log "Stopping app to drop active connections..."
    wv_compose stop app || true

    wv_log "Terminating any remaining connections to wealthview..."
    wv_compose exec -T db psql -U wv_app -d postgres -c \
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='wealthview' AND pid <> pg_backend_pid();" \
        >/dev/null 2>&1 || true

    wv_log "Running pg_restore..."
    if ! wv_compose exec -T db pg_restore --clean --if-exists -U wv_app -d wealthview < "$feed_path"; then
        wv_warn "pg_restore reported errors; this is often harmless for --clean (objects may not exist)."
    fi

    wv_log "Starting app..."
    wv_compose start app || wv_compose up -d app

    if wv_wait_healthy 90; then
        wv_log "Restore complete."
    else
        wv_warn "App did not pass health check after restore; inspect logs."
        return 1
    fi
}
