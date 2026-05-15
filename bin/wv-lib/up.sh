# shellcheck shell=bash
# up.sh — bring the stack up.

wv_up() {
    local build="--build" detach="-d" wait_health=1
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv up — start the WealthView stack.

OPTIONS
    --no-build      skip the image rebuild step
    --no-detach     run in the foreground (hold the terminal)
    --no-wait       do not wait for the health check to pass
USAGE
                return 0
                ;;
            --no-build) build=""; shift ;;
            --no-detach) detach=""; shift ;;
            --no-wait) wait_health=0; shift ;;
            *) wv_die "Unknown option for 'up': $1" ;;
        esac
    done

    wv_env_check || wv_die "Fix .env before bringing the stack up."

    local mode
    mode="$(wv_mode)"
    wv_log "Starting WealthView ($mode mode) using $(wv_compose_file)"
    # shellcheck disable=SC2086  # we want word-splitting on the optional flags
    wv_compose up $build $detach
    if [[ -n "$detach" && "$wait_health" == "1" ]]; then
        wv_wait_healthy 120 || {
            wv_warn "Stack started but health check did not pass."
            wv_warn "Inspect with: ./wv logs app"
            return 1
        }
    fi
}
