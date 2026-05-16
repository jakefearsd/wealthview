# shellcheck shell=bash
# restart.sh — stop the stack and bring it back up. Convenience wrapper for
# the common "I changed config, recycle everything" flow. Database data
# volumes are preserved (we use `down` without --volumes).

wv_restart() {
    local up_args=()
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
wv restart — stop the stack and start it again.

Equivalent to: wv down && wv up.

Database volumes are preserved. Any extra arguments after `restart` are
forwarded to `wv up` (e.g. --no-build, --no-wait).

EXAMPLES
    wv restart
    wv restart --no-build
USAGE
                return 0
                ;;
            *)
                up_args+=("$1")
                shift
                ;;
        esac
    done

    # shellcheck source=down.sh
    . "$WV_LIB_DIR/down.sh"
    # shellcheck source=up.sh
    . "$WV_LIB_DIR/up.sh"

    wv_log "Restart: stopping..."
    wv_down || wv_warn "wv down returned non-zero; continuing to up."
    wv_log "Restart: starting..."
    wv_up "${up_args[@]}"
}
