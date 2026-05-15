# shellcheck shell=bash
# migrate-out.sh — produce a portable bundle of the live data so the deployment
# can be moved to another host.

wv_migrate_out() {
    local dest="" encrypt_required=1
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv migrate-out — produce a portable bundle for moving to another host.

Bundle contents:
  - encrypted database backup (.dump.age) taken at migration time
  - .env.example skeleton for the new host
  - VERSION pin (the WEALTHVIEW_VERSION currently running)
  - infra/ directory (backup container source)
  - README.txt with restore instructions

OPTIONS
    --dest DIR          where to write the bundle (default: backups/migrations/)
    --no-encrypt        skip encryption (NOT RECOMMENDED; bundle contains DB)
USAGE
                return 0
                ;;
            --dest)
                shift
                [[ $# -gt 0 ]] || wv_die "--dest requires a directory"
                dest="$1"
                shift
                ;;
            --no-encrypt) encrypt_required=0; shift ;;
            *) wv_die "Unknown option for 'migrate-out': $1" ;;
        esac
    done

    if (( encrypt_required == 1 )); then
        local recipient="${BACKUP_ENCRYPTION_RECIPIENT:-$(wv_env_get BACKUP_ENCRYPTION_RECIPIENT)}"
        [[ -n "$recipient" ]] \
            || wv_die "BACKUP_ENCRYPTION_RECIPIENT must be set (an age public key) or use --no-encrypt."
    fi

    [[ -z "$dest" ]] && dest="$(wv_backups_dir)/migrations"
    mkdir -p "$dest"

    local ts staging
    ts="$(wv_timestamp)"
    staging="$(mktemp -d -t wv-migrate-XXXXXX)"
    # shellcheck disable=SC2064  # expand $staging now, on purpose
    trap "rm -rf '$staging'" RETURN

    wv_log "Taking migration backup..."
    # shellcheck source=scripts/wv-lib/backup.sh
    . "$WV_LIB_DIR/backup.sh"

    local backup_path
    if (( encrypt_required == 1 )); then
        backup_path="$(wv_backup --encrypt --label migration | tail -n 1)"
    else
        backup_path="$(wv_backup --label migration | tail -n 1)"
    fi
    [[ -f "$backup_path" ]] || wv_die "Migration backup did not produce a file."

    cp "$backup_path" "$staging/"
    [[ -f "$WV_ROOT/.env.example" ]] && cp "$WV_ROOT/.env.example" "$staging/"
    [[ -d "$WV_ROOT/infra" ]] && cp -r "$WV_ROOT/infra" "$staging/"

    local version
    version="$(wv_env_get WEALTHVIEW_VERSION)"
    printf 'WEALTHVIEW_VERSION=%s\n' "${version:-unset}" > "$staging/VERSION.pin"

    cat > "$staging/README.txt" <<EOF
WealthView migration bundle.
Created: $ts (UTC)

Contents:
  - $(basename "$backup_path")   — database backup (encrypted with age unless --no-encrypt was used)
  - .env.example                 — skeleton; fill in secrets on the new host
  - infra/                       — backup container source
  - VERSION.pin                  — version of the app this backup was taken against

To restore on the new host:
  1. Clone or rsync the wealthview repo at the version listed in VERSION.pin.
  2. cp .env.example .env  (or copy it from this bundle and re-fill).
  3. Set BACKUP_ENCRYPTION_KEY_FILE in .env to point at your age identity.
  4. ./wv up
  5. ./wv migrate-in <this-bundle>
EOF

    local bundle_name="wealthview-bundle-${ts}.tar.gz"
    local bundle_path="${dest}/${bundle_name}"
    tar -C "$staging" -czf "$bundle_path" .

    wv_log "Bundle ready: $bundle_path"
    printf '%s\n' "$bundle_path"
}
