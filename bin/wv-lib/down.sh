# shellcheck shell=bash
# down.sh — stop the stack (data preserved by default).

wv_down() {
    local remove_volumes=0
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv down — stop the WealthView stack.

By default, named volumes (database data, backups) are preserved. Use
--with-volumes to wipe them; this is destructive and prompts for confirmation.

OPTIONS
    --with-volumes  also delete named volumes (DESTROYS database data)
USAGE
                return 0
                ;;
            --with-volumes) remove_volumes=1; shift ;;
            *) wv_die "Unknown option for 'down': $1" ;;
        esac
    done

    if (( remove_volumes == 1 )); then
        wv_warn "--with-volumes will DELETE the database volume permanently."
        wv_confirm "Type Y to confirm volume deletion" || {
            wv_log "Aborted."
            return 1
        }
        wv_compose down -v
    else
        wv_compose down
    fi
}
