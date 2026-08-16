# shellcheck shell=bash
# update.sh — pre-update backup -> pull image -> swap -> health-check ->
# auto-rollback on failure.
#
# The new image is PULLED from the registry, not built here. CI publishes it on
# a v* tag after the unit, quality-gate and full integration suites pass, so the
# artifact reaching production is one that was actually verified — and the host
# needs neither the source tree nor a JDK. Point WEALTHVIEW_VERSION at the
# release you want, then run this.
#
# `--build` remains for the dev stack and for hosts that genuinely must build
# locally (an air-gapped box, or bisecting an unreleased commit). It requires a
# compose file with a `build:` key; docker-compose.prod.yml deliberately has none.

# Where we record the previously-running image so `wv rollback` can find it.
# The path is resolved in common.sh and may be overridden via wv.conf.
WV_PREVIOUS_IMAGE_FILE() { printf '%s\n' "$WV_PREVIOUS_IMAGE_FILE"; }

# Split an image reference into repository and tag.
#
# The app image is now a fully-qualified registry reference
# (ghcr.io/<owner>/wealthview:1.2.5), so rollback can no longer recover the tag
# by stripping a literal "wealthview:" prefix. Splitting on the LAST colon is
# also not enough on its own: "registry.internal:5000/wealthview" has a colon
# for the registry port and no tag at all, and mistaking that for a tag would
# re-pin the app to a version literally named "5000". A colon only introduces a
# tag when nothing after it contains a slash.
_wv_image_tag() {
    local ref="$1" last="${1##*:}"
    [[ "$last" != "$ref" ]] || return 1   # no colon at all
    [[ "$last" != */* ]] || return 1      # colon belonged to a registry host:port
    printf '%s\n' "$last"
}

_wv_image_repo() {
    local ref="$1"
    _wv_image_tag "$ref" >/dev/null || return 1
    printf '%s\n' "${ref%:*}"
}

# How to obtain the new image when the caller did not say.
#
# This follows the deployment mode rather than being fixed: the prod compose
# file is image-only and must pull, while the dev file is build-only (its app
# service has no `image:` key at all) and cannot be pulled. An explicit
# --build / --no-pull always overrides this.
_wv_update_default_acquire() {
    if [[ "$(wv_mode)" == "prod" ]]; then
        printf 'pull\n'
    else
        printf 'build\n'
    fi
}

# True when the resolved compose file gives the app service a `build:` section.
# Used to reject `--build` loudly instead of letting compose no-op into a stale
# deploy. python3 is already a hard requirement (see `wv config-check`).
_wv_update_app_is_buildable() {
    wv_compose config --format json 2>/dev/null | python3 -c '
import json, sys
try:
    cfg = json.load(sys.stdin)
except Exception:
    sys.exit(1)
sys.exit(0 if cfg.get("services", {}).get("app", {}).get("build") else 1)
' 2>/dev/null
}

wv_update() {
    local acquire auto_rollback=1
    acquire="$(_wv_update_default_acquire)"
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv update — safe in-place upgrade.

In prod mode, pulls the release image published by CI, swaps the app container
onto it, and rolls back automatically if it does not come up healthy. Set
WEALTHVIEW_VERSION in the env file to the version you want BEFORE running this.

In dev mode (no WEALTHVIEW_VERSION set) the image is built locally instead,
since the dev compose file builds from source and has nothing to pull.

Sequence:
    1. Validate .env.
    2. Take a labelled pre-update backup.
    3. Record currently running app image so rollback has a target.
    4. Pull the image the compose file resolves (or build it, with --build).
    5. Re-create the app container with the new image.
    6. Wait for the health endpoint to pass.
    7. On health failure: roll back to the previously running image
       (unless --no-rollback was given).

OPTIONS
    --build         build the image locally instead of pulling. Requires a
                    compose file with a `build:` key — docker-compose.prod.yml
                    has none, since production deploys verified CI artifacts.
    --no-pull       skip step 4 entirely; reuse whatever image is already
                    present locally for the resolved tag
    --no-build      deprecated alias for --no-pull
    --no-rollback   leave the failing container in place for inspection

EXIT CODES
    0   update succeeded and is healthy
    1   update failed; rollback succeeded (if enabled)
    2   update failed; rollback also failed -> manual intervention needed
USAGE
                return 0
                ;;
            --build) acquire="build"; shift ;;
            --no-pull) acquire="none"; shift ;;
            --no-build)
                # Kept working on purpose: it is in muscle memory and in older
                # runbooks. Under the pull-based flow "don't build" means
                # "don't fetch a new image", which is what --no-pull does.
                wv_warn "--no-build is deprecated; use --no-pull (same effect)."
                acquire="none"
                shift
                ;;
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

    # 3. Acquire the new image.
    case "$acquire" in
        pull)
            wv_log "Step 3/5: Pulling new image."
            wv_compose pull app || {
                wv_err "Image pull failed."
                wv_err "Check WEALTHVIEW_VERSION in $WV_ENV_FILE names a published release,"
                wv_err "and that this host can read the registry (a private GHCR package"
                wv_err "needs 'docker login ghcr.io' with a read:packages token)."
                return 1
            }
            ;;
        build)
            # `docker compose build` does NOT fail when the named service has no
            # `build:` key — it quietly builds nothing and exits 0. That would
            # deploy a stale image while reporting success, so check first.
            if ! _wv_update_app_is_buildable; then
                wv_err "--build was requested, but the app service in $(wv_compose_file)"
                wv_err "has no 'build:' key, so there is nothing to build."
                wv_err "docker-compose.prod.yml is image-only by design: production runs"
                wv_err "the CI-verified image from the registry. Drop --build to pull it."
                return 1
            fi
            wv_log "Step 3/5: Building new image locally (--build)."
            wv_compose build app || {
                wv_err "Image build failed."
                return 1
            }
            ;;
        none)
            wv_log "Step 3/5: Skipping image fetch (--no-pull)."
            ;;
        *)
            wv_die "Internal error: unknown image acquisition mode '$acquire'."
            ;;
    esac

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
    # Re-pin the app image via env vars. The prod compose file resolves it as
    # ${WEALTHVIEW_IMAGE}:${WEALTHVIEW_VERSION}, so both halves are restored —
    # setting only the tag would silently move the deployment to the default
    # repository if the previous image came from somewhere else (a mirror, a
    # fork's registry). The dev file builds locally, so `up -d` is enough there.
    if [[ "$(wv_mode)" == "prod" ]]; then
        local tag repo
        if tag="$(_wv_image_tag "$target")" && repo="$(_wv_image_repo "$target")"; then
            WEALTHVIEW_IMAGE="$repo" WEALTHVIEW_VERSION="$tag" \
                wv_compose up -d --no-deps app
        else
            wv_err "Previous image '$target' has no parseable tag, so it cannot be"
            wv_err "re-pinned. Set WEALTHVIEW_VERSION in $WV_ENV_FILE by hand and run"
            wv_err "'wv up' to recover."
            return 1
        fi
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
