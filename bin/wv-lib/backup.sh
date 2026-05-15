# shellcheck shell=bash
# backup.sh — produce a pg_dump and optionally encrypt + push off-host.

wv_backup() {
    local encrypt=0 remote=0 label="" dry_run=0
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv backup — take a pg_dump backup of the live wealthview database.

OPTIONS
    --encrypt           encrypt the dump with age (requires
                        BACKUP_ENCRYPTION_RECIPIENT in env or .env, an
                        age public key like "age1...")
    --remote            best-effort copy of the final file to BACKUP_REMOTE_DEST
                        (e.g. s3://bucket/path/, user@host:/dir/, rsync://...).
                        Local backup still succeeds even if the upload fails.
    --label TEXT        append _TEXT to the filename (alphanumerics + dashes only)
    --dry-run           print the operations that would happen, take no action
USAGE
                return 0
                ;;
            --encrypt) encrypt=1; shift ;;
            --remote) remote=1; shift ;;
            --label)
                shift
                [[ $# -gt 0 ]] || wv_die "--label requires a value"
                label="$1"
                if ! [[ "$label" =~ ^[A-Za-z0-9_-]+$ ]]; then
                    wv_die "Label must contain only letters, digits, dash, underscore"
                fi
                shift
                ;;
            --dry-run) dry_run=1; shift ;;
            *) wv_die "Unknown option for 'backup': $1" ;;
        esac
    done

    local backups_dir
    backups_dir="$(wv_backups_dir)"
    mkdir -p "$backups_dir"

    local ts base name path
    ts="$(wv_timestamp)"
    base="wealthview_${ts}"
    [[ -n "$label" ]] && base="${base}_${label}"
    name="${base}.dump"
    path="${backups_dir}/${name}"

    if (( dry_run == 1 )); then
        wv_log "[dry-run] Would dump db -> $path"
        (( encrypt == 1 )) && wv_log "[dry-run] Would encrypt to ${path}.age"
        if (( remote == 1 )); then
            local dest="${BACKUP_REMOTE_DEST:-$(wv_env_get BACKUP_REMOTE_DEST)}"
            wv_log "[dry-run] Would push to ${dest:-(unset; --remote would no-op)}"
        fi
        return 0
    fi

    wv_log "Dumping wealthview database -> $path"
    if ! wv_compose exec -T db pg_dump -Fc -U wv_app wealthview > "$path"; then
        rm -f "$path"
        wv_die "pg_dump failed"
    fi
    local size
    size="$(du -h "$path" | cut -f1)"
    wv_log "Backup written: $path ($size)"

    if (( encrypt == 1 )); then
        wv_require age
        local recipient="${BACKUP_ENCRYPTION_RECIPIENT:-$(wv_env_get BACKUP_ENCRYPTION_RECIPIENT)}"
        if [[ -z "$recipient" ]]; then
            wv_die "--encrypt requires BACKUP_ENCRYPTION_RECIPIENT (an age public key like age1...) in .env or env."
        fi
        local enc_path="${path}.age"
        if ! age -r "$recipient" -o "$enc_path" "$path"; then
            wv_die "age encryption failed; plaintext dump kept at $path"
        fi
        # Replace plaintext with encrypted to avoid leaving plaintext on disk.
        rm -f "$path"
        path="$enc_path"
        wv_log "Encrypted: $path"
    fi

    if (( remote == 1 )); then
        local dest="${BACKUP_REMOTE_DEST:-$(wv_env_get BACKUP_REMOTE_DEST)}"
        if [[ -z "$dest" ]]; then
            wv_warn "--remote was requested but BACKUP_REMOTE_DEST is not set; skipping upload."
        else
            wv_log "Pushing to $dest"
            # Best-effort: a failed off-host copy must NOT fail the backup.
            if _wv_backup_push "$path" "$dest"; then
                wv_log "Off-host copy succeeded."
            else
                wv_warn "Off-host copy failed; local backup at $path is intact."
            fi
        fi
    fi

    printf '%s\n' "$path"
}

# Internal: dispatch by destination scheme.
_wv_backup_push() {
    local src="$1" dest="$2"
    case "$dest" in
        s3://*)
            if ! command -v aws >/dev/null 2>&1; then
                wv_warn "aws CLI not installed; cannot push to $dest"
                return 1
            fi
            aws s3 cp "$src" "$dest"
            ;;
        rsync://*|*:*)
            if ! command -v rsync >/dev/null 2>&1; then
                wv_warn "rsync not installed; cannot push to $dest"
                return 1
            fi
            rsync -av "$src" "$dest"
            ;;
        *)
            wv_warn "Unrecognised BACKUP_REMOTE_DEST scheme: $dest (expected s3://, rsync://, or user@host:/path)"
            return 1
            ;;
    esac
}
