# shellcheck shell=bash
# migrate-in.sh — consume a bundle produced by migrate-out: extract, decrypt,
# restore database. Stack must already be up.

wv_migrate_in() {
    local bundle=""
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv migrate-in <bundle.tar.gz> — restore from a migration bundle.

Steps:
    1. Extract bundle to a temporary directory.
    2. Locate the .dump (or .dump.age) inside.
    3. Run ./wv restore on the dump (decryption automatic for .age).
    4. Print the recorded WEALTHVIEW_VERSION so the operator can pin .env.

The wealthview stack must already be running (./wv up).
USAGE
                return 0
                ;;
            -*) wv_die "Unknown option for 'migrate-in': $1" ;;
            *)
                if [[ -n "$bundle" ]]; then
                    wv_die "migrate-in takes exactly one bundle argument."
                fi
                bundle="$1"
                shift
                ;;
        esac
    done

    [[ -n "$bundle" ]] || { wv_err "Usage: ./wv migrate-in <bundle.tar.gz>"; return 2; }
    [[ -f "$bundle" ]] || wv_die "Bundle not found: $bundle"

    local staging
    staging="$(mktemp -d -t wv-migrate-in-XXXXXX)"
    # Register with the shared cleanup registry, NEVER a bare `trap ... EXIT`.
    # bash keeps exactly one EXIT trap per shell and wv runs every subcommand in
    # that one shell, so the wv_restore call below — which sources restore.sh and
    # registers its own cleanup for the decrypted dump — would REPLACE a trap set
    # here, leaking this staging directory (the database dump plus the copied
    # infra/ tree). migrate-out encrypts by default, so that was the normal path,
    # not an edge case. wv_on_exit accumulates on a LIFO stack instead of
    # clobbering, so both registrations survive.
    wv_on_exit "rm -rf '$staging'"

    wv_log "Extracting bundle..."
    tar -C "$staging" -xzf "$bundle"

    local dump
    dump="$(find "$staging" -maxdepth 2 -type f \
        \( -name '*.dump' -o -name '*.dump.age' \) | head -n 1)"
    [[ -n "$dump" ]] || wv_die "No .dump file found in bundle."

    wv_log "Bundle dump: $dump"
    if [[ -f "$staging/VERSION.pin" ]]; then
        wv_log "Bundle WEALTHVIEW_VERSION pin:"
        cat "$staging/VERSION.pin"
    fi

    # shellcheck source=bin/wv-lib/restore.sh
    . "$WV_LIB_DIR/restore.sh"
    wv_restore "$dump"
}
