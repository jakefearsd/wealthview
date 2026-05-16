# shellcheck shell=bash
# update.sh — pre-update backup -> rebuild image -> swap -> health-check ->
# auto-rollback on failure.

# Where we record the previously-running image so `wv rollback` can find it.
# The path is resolved in common.sh and may be overridden via wv.conf.
WV_PREVIOUS_IMAGE_FILE() { printf '%s\n' "$WV_PREVIOUS_IMAGE_FILE"; }

wv_update() {
    local do_build=1 auto_rollback=1
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv update — safe in-place upgrade.

Sequence:
    1. Validate .env.
    2. Take a labelled pre-update backup.
    3. Record currently running app image so rollback has a target.
    4. (Optional) docker compose build the new image.
    5. Re-create the app container with the new image.
    6. Wait for the health endpoint to pass.
    7. On health failure: roll back to the previously running image
       (unless --no-rollback was given).

OPTIONS
    --no-build      do not rebuild; reuse whatever the compose file resolves
    --no-rollback   leave the failing container in place for inspection

EXIT CODES
    0   update succeeded and is healthy
    1   update failed; rollback succeeded (if enabled)
    2   update failed; rollback also failed -> manual intervention needed
USAGE
                return 0
                ;;
            --no-build) do_build=0; shift ;;
            --no-rollback) auto_rollback=0; shift ;;
            *) wv_die "Unknown option for 'update': $1" ;;
        esac
    done

    wv_env_check || wv_die "Fix .env before running update."

    # 1. Pre-update backup. Note: this requires the stack to be currently up.
    if ! wv_compose ps --status running --services 2>/dev/null | grep -qx db; then
        wv_die "db container is not running; run './wv up' first."
    fi

    wv_log "Step 1/5: Taking pre-update backup."
    # shellcheck source=bin/wv-lib/backup.sh
    . "$WV_LIB_DIR/backup.sh"
    local backup_path
    backup_path="$(wv_backup --label pre-update | tail -n 1)" \
        || wv_die "Pre-update backup failed; aborting update."
    wv_log "Pre-update backup: $backup_path"

    # 2. Record current image for rollback.
    wv_log "Step 2/5: Recording current app image."
    local current_image
    current_image="$(wv_compose images app --format json 2>/dev/null \
        | python3 -c "import json,sys
try:
    data=json.loads(sys.stdin.read())
except Exception:
    print('')
    sys.exit(0)
if isinstance(data, dict):
    data=[data]
for d in data:
    repo=d.get('Repository','')
    tag=d.get('Tag','')
    if repo:
        print(f'{repo}:{tag}' if tag else repo)
        break" 2>/dev/null)"
    if [[ -n "$current_image" ]]; then
        printf '%s\n' "$current_image" > "$(WV_PREVIOUS_IMAGE_FILE)"
        wv_log "Saved previous image: $current_image"
    else
        wv_warn "Could not determine current app image; rollback may be unavailable."
    fi

    # 3. Build new image (unless skipped).
    if (( do_build == 1 )); then
        wv_log "Step 3/5: Building new image."
        wv_compose build app || {
            wv_err "Image build failed."
            return 1
        }
    else
        wv_log "Step 3/5: Skipping build (--no-build)."
    fi

    # 4. Recreate the app container.
    wv_log "Step 4/5: Recreating app container."
    if ! wv_compose up -d --no-deps app; then
        wv_err "docker compose up failed."
        if (( auto_rollback == 1 )); then
            _wv_update_rollback "$current_image" || return 2
            return 1
        fi
        return 1
    fi

    # 5. Health check + auto-rollback on failure.
    wv_log "Step 5/5: Waiting for health check."
    if wv_wait_healthy 180; then
        wv_log "Update complete and healthy."
        return 0
    fi

    wv_err "Update failed health check."
    if (( auto_rollback == 1 )) && [[ -n "$current_image" ]]; then
        _wv_update_rollback "$current_image" || return 2
        return 1
    fi
    wv_warn "Auto-rollback disabled or no previous image recorded; leaving in place."
    return 1
}

# Roll back to a specific image tag. Used by both wv update and wv rollback.
_wv_update_rollback() {
    local target="$1"
    [[ -n "$target" ]] || { wv_err "No previous image to roll back to."; return 1; }
    wv_warn "Rolling back app to: $target"
    # Run a one-off compose service that pins the image via env var. We rely on
    # the convention: the prod compose file uses ${WEALTHVIEW_VERSION}; the dev
    # file builds locally. For dev, we re-use the build cache via `up -d`.
    if [[ "$(wv_mode)" == "prod" ]]; then
        local tag="${target#wealthview:}"
        if [[ "$tag" == "$target" ]]; then
            wv_warn "Previous image '$target' has unexpected format; expected wealthview:<tag>."
        fi
        WEALTHVIEW_VERSION="$tag" wv_compose up -d --no-deps app
    else
        wv_compose up -d --no-deps app
    fi
    if wv_wait_healthy 120; then
        wv_log "Rollback succeeded."
        return 0
    fi
    wv_err "Rollback also failed health check; manual intervention required."
    return 1
}
