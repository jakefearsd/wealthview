# shellcheck shell=bash
# logs.sh — tail container logs.

wv_logs() {
    local service="" tail="" follow="-f"
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv logs [service] — tail container logs.

ARGUMENTS
    service     name of a compose service (db, app, backup). Default: all services.

OPTIONS
    --tail N    show only the last N lines, then keep following
    --no-follow do not stream new lines; print and exit
USAGE
                return 0
                ;;
            --tail)
                shift
                [[ $# -gt 0 ]] || wv_die "--tail requires a number"
                tail="--tail $1"
                shift
                ;;
            --no-follow) follow=""; shift ;;
            -*) wv_die "Unknown option for 'logs': $1" ;;
            *) service="$1"; shift ;;
        esac
    done

    # shellcheck disable=SC2086  # word-splitting on optional flags is intentional
    if [[ -n "$service" ]]; then
        wv_compose logs $follow $tail "$service"
    else
        wv_compose logs $follow $tail
    fi
}
