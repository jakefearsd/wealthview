# shellcheck shell=bash
# rollback.sh — revert the app container to the image saved by the last
# successful wv update.

wv_rollback() {
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv rollback — revert the app to the previous image.

Reads .wv-previous-image (written by ./wv update) and restarts the app
container pinned to that tag. Database state is NOT rewound; if you also
need to roll back the schema/data, restore the matching pre-update backup
with ./wv restore first.
USAGE
                return 0
                ;;
            *) wv_die "Unknown option for 'rollback': $1" ;;
        esac
    done

    # shellcheck source=scripts/wv-lib/update.sh
    . "$WV_LIB_DIR/update.sh"

    local file
    file="$(WV_PREVIOUS_IMAGE_FILE)"
    [[ -f "$file" ]] || wv_die "No previous image recorded; cannot rollback. (Expected $file.)"

    local prev
    prev="$(cat "$file")"
    [[ -n "$prev" ]] || wv_die "Previous image file is empty: $file"

    wv_log "Rolling back to: $prev"
    _wv_update_rollback "$prev"
}
