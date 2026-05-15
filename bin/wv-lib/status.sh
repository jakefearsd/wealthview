# shellcheck shell=bash
# status.sh — show container status and health probe.

wv_status() {
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv status — show container status and run a single health probe.
USAGE
                return 0
                ;;
            *) wv_die "Unknown option for 'status': $1" ;;
        esac
    done

    wv_log "Mode: $(wv_mode)"
    wv_log "Compose file: $(wv_compose_file)"
    echo
    wv_compose ps
    echo

    local port
    port="$(wv_env_get APP_PORT)"
    port="${port:-80}"
    local url="http://localhost:${port}/actuator/health"
    if curl -fsS --max-time 5 "$url" 2>/dev/null; then
        echo
        wv_log "Health endpoint reachable at $url"
    else
        echo
        wv_warn "Health endpoint NOT reachable at $url"
    fi
}
