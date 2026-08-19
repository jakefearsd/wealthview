# shellcheck shell=bash
# prune.sh — reclaim disk from dangling WealthView images.

# Every image built from our Dockerfile carries this OCI label (the runtime
# stage sets it; CI's build-push-action sets the same key), which is the ONLY
# thing that lets us scope a prune to this project. `docker system prune` is
# host-wide and would delete images belonging to unrelated projects sharing the
# same daemon — this label is what we filter on instead.
WV_PRUNE_IMAGE_LABEL="org.opencontainers.image.title=WealthView"

# Docker invocation shared by every code path below. Subcommands normally reach
# docker through wv_compose, which maps WV_HOST onto DOCKER_HOST; prune talks to
# `docker` directly (there is no compose equivalent of `image rm`), so it has to
# replicate that mapping rather than silently pruning the operator's laptop when
# they meant the remote host.
_wv_prune_docker() {
    if [[ -n "${WV_HOST:-}" ]]; then
        DOCKER_HOST="ssh://${WV_HOST}" docker "$@"
    else
        docker "$@"
    fi
}

wv_prune() {
    local dry_run=0
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv prune — remove dangling (untagged) WealthView images.

The dev stack builds the app image as wealthview-app:latest on every `wv up`.
Docker moves that tag to the new image and leaves the old one behind as an
untagged <none> layer set — roughly 300MB orphaned per rebuild. This removes
those, and only those.

Deliberately narrower than `docker system prune`: that command is host-wide and
would delete images, containers, networks and build cache belonging to every
other project on this daemon. `wv prune` only ever removes images that are BOTH
dangling AND labelled org.opencontainers.image.title=WealthView. It never
touches named volumes (that would destroy the database), containers, networks,
or any tagged image — including the one currently running.

OPTIONS
    --dry-run   list what would be removed and reclaimed, remove nothing
USAGE
                return 0
                ;;
            --dry-run) dry_run=1; shift ;;
            *) wv_die "Unknown option for 'prune': $1" ;;
        esac
    done

    wv_require docker

    # Both filters are required. dangling=true alone would match every other
    # project's orphans; the label alone would match our live tagged images.
    local raw
    raw="$(_wv_prune_docker images --filter dangling=true \
        --filter "label=${WV_PRUNE_IMAGE_LABEL}" -q | sort -u)" \
        || wv_die "Could not list images. Is the Docker daemon reachable?"

    local -a ids=()
    [[ -n "$raw" ]] && mapfile -t ids <<< "$raw"

    if (( ${#ids[@]} == 0 )); then
        wv_log "Nothing to prune: no dangling WealthView images."
        return 0
    fi

    local id size total=0

    if (( dry_run == 1 )); then
        wv_log "[dry-run] Would remove ${#ids[@]} dangling WealthView image(s):"
        for id in "${ids[@]}"; do
            size="$(_wv_prune_image_size "$id")"
            total=$((total + size))
            printf '    %-14s %10s\n' "$id" "$(_wv_human_bytes "$size")"
        done
        wv_log "[dry-run] Would reclaim $(_wv_human_bytes "$total"). Nothing was removed."
        return 0
    fi

    # Size is read BEFORE removal (afterwards the image is gone and can't be
    # inspected), and images go one at a time so a single failure doesn't abort
    # the rest of the sweep.
    local removed=0 failed=0
    for id in "${ids[@]}"; do
        size="$(_wv_prune_image_size "$id")"
        if _wv_prune_docker image rm "$id" >/dev/null 2>&1; then
            removed=$((removed + 1))
            total=$((total + size))
        else
            # No `-f` here on purpose: a dangling image that refuses to go is
            # still referenced (usually by a stopped container), and forcing it
            # would break whatever holds it. Report and move on.
            failed=$((failed + 1))
            wv_warn "Could not remove $id — still referenced by a container. Left in place."
        fi
    done

    wv_log "Removed ${removed} image(s), reclaimed $(_wv_human_bytes "$total")."
    if (( failed > 0 )); then
        wv_warn "${failed} image(s) skipped. Run 'docker ps -a' to find what still references them."
    fi
    return 0
}

_wv_prune_image_size() {
    # Echoes the image's size in bytes, or 0 if it can't be determined (the
    # image may have been removed by a concurrent build between listing and now).
    local bytes
    bytes="$(_wv_prune_docker image inspect --format '{{.Size}}' "$1" 2>/dev/null)" || bytes=""
    [[ "$bytes" =~ ^[0-9]+$ ]] || bytes=0
    printf '%s\n' "$bytes"
}

_wv_human_bytes() {
    # Integer-only formatting: bash has no floating point, and pulling in bc or
    # awk for a size string isn't worth the dependency.
    local bytes="${1:-0}"
    if (( bytes >= 1073741824 )); then
        printf '%d.%02dGB' "$((bytes / 1073741824))" "$(((bytes % 1073741824) * 100 / 1073741824))"
    elif (( bytes >= 1048576 )); then
        printf '%d.%02dMB' "$((bytes / 1048576))" "$(((bytes % 1048576) * 100 / 1048576))"
    elif (( bytes >= 1024 )); then
        printf '%dKB' "$((bytes / 1024))"
    else
        printf '%dB' "$bytes"
    fi
}
