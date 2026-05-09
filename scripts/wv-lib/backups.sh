# shellcheck shell=bash
# backups.sh — list available backups with size and age.

wv_backups() {
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv backups — list backup files with size and age.

Looks for wealthview_*.dump and wealthview_*.dump.age in ./backups/.
USAGE
                return 0
                ;;
            *) wv_die "Unknown option for 'backups': $1" ;;
        esac
    done

    local dir
    dir="$(wv_backups_dir)"
    if [[ ! -d "$dir" ]]; then
        wv_log "No backups directory yet at $dir"
        return 0
    fi

    # shellcheck disable=SC2207  # filenames here can't contain spaces (we control them)
    local files=( $(find "$dir" -maxdepth 1 -type f \
        \( -name 'wealthview_*.dump' -o -name 'wealthview_*.dump.age' \) \
        -printf '%T@ %p\n' 2>/dev/null | sort -rn | awk '{print $2}') )
    if (( ${#files[@]} == 0 )); then
        wv_log "No backups in $dir"
        return 0
    fi

    printf '%-50s %10s  %s\n' 'FILE' 'SIZE' 'AGE'
    local f size mtime now age_human
    now="$(date +%s)"
    for f in "${files[@]}"; do
        size="$(du -h "$f" | cut -f1)"
        mtime="$(stat -c %Y "$f" 2>/dev/null || echo "$now")"
        age_human="$(_wv_age "$((now - mtime))")"
        printf '%-50s %10s  %s\n' "$(basename "$f")" "$size" "$age_human"
    done
}

_wv_age() {
    local s="$1"
    if (( s < 60 )); then printf '%ds ago' "$s"
    elif (( s < 3600 )); then printf '%dm ago' "$((s / 60))"
    elif (( s < 86400 )); then printf '%dh %dm ago' "$((s / 3600))" "$(((s % 3600) / 60))"
    else printf '%dd %dh ago' "$((s / 86400))" "$(((s % 86400) / 3600))"
    fi
}
