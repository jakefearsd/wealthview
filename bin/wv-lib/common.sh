# shellcheck shell=bash
# common.sh — shared helpers loaded by every wv subcommand.
#
# This file is sourced by bin/wv with `set -euo pipefail` already in effect.
# Functions here MUST be safe to call from any subcommand and must NOT exit
# unconditionally — use wv_die for fatal paths so callers can preserve a
# meaningful exit code.

# --- Output helpers. -------------------------------------------------------
wv_log() { printf '==> %s\n' "$*"; }
wv_warn() { printf 'WARN: %s\n' "$*" >&2; }
wv_err() { printf 'ERROR: %s\n' "$*" >&2; }

wv_die() {
    wv_err "$*"
    exit 1
}

# --- Cleanup registry. ------------------------------------------------------
# bash has exactly ONE EXIT trap per shell, and `wv` runs every subcommand in a
# single shell process (bin/wv sources the library and calls the function). A
# subcommand that installs its own `trap ... EXIT` therefore silently REPLACES
# any trap an outer command already set: `wv migrate-in` registered its staging
# directory for deletion, then sourced restore.sh, whose own EXIT trap wiped
# that registration out and leaked the extracted bundle on every encrypted
# restore.
#
# RETURN traps are not an escape hatch either, for two reasons that bit this
# codebase:
#   1. `exit` does NOT run RETURN traps, so every wv_die path skipped cleanup —
#      that is how `wv verify` left a live postgres container and a DECRYPTED
#      database dump behind when the readiness probe timed out.
#   2. A RETURN trap fires when a SOURCED SCRIPT completes, not only when the
#      function that set it returns. `wv migrate-out` set one and then sourced
#      backup.sh, which fired the trap and deleted its own staging directory
#      mid-run.
#
# So: register cleanup actions here instead of calling `trap` directly. Actions
# are shell command strings, run LIFO (innermost first), each isolated so one
# failure cannot stop the rest. Use single quotes and pre-expand any variable
# you need, exactly as with a trap string.
#
#   wv_on_exit "docker rm -fv '$container' >/dev/null 2>&1"
#   wv_on_exit "rm -rf '$staging'"
WV_CLEANUP_ACTIONS=()

wv_on_exit() {
    WV_CLEANUP_ACTIONS+=("$1")
}

_wv_run_cleanup() {
    # Preserve the exit status we were invoked with — cleanup must never change
    # the script's result.
    local rc=$?
    local i
    for (( i = ${#WV_CLEANUP_ACTIONS[@]} - 1; i >= 0; i-- )); do
        eval "${WV_CLEANUP_ACTIONS[i]}" || true
    done
    # Clearing the list makes a second invocation a no-op, so the signal traps
    # below can run cleanup eagerly without it happening twice via EXIT.
    WV_CLEANUP_ACTIONS=()
    return "$rc"
}

# Signals get an explicit handler: relying on EXIT alone is not portable enough
# to bet a running container on, and 130/143 are the conventional statuses.
trap '_wv_run_cleanup; exit 130' INT
trap '_wv_run_cleanup; exit 143' TERM
trap _wv_run_cleanup EXIT

# --- Required-binary guard. ------------------------------------------------
wv_require() {
    # wv_require docker docker-compose age
    local missing=()
    for bin in "$@"; do
        if ! command -v "$bin" >/dev/null 2>&1; then
            missing+=("$bin")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        wv_die "Missing required tool(s): ${missing[*]}. Install them and retry."
    fi
}

# ===========================================================================
# Configuration resolution
# ===========================================================================
#
# wv is designed to run both in the source tree (dev) and on a server with
# only containers installed (prod). To support that, we resolve a small set
# of variables — compose file, env file, backups dir, optional remote host —
# in this order, first match wins:
#
#   1. WV_CONFIG_FILE  (set by --config or already in environment)
#   2. /etc/wealthview/wv.conf
#   3. $XDG_CONFIG_HOME/wealthview/wv.conf
#   4. $HOME/.config/wealthview/wv.conf
#   5. Source-tree fallback: project root (parent of bin/) is treated as
#      the install dir, with docker-compose[.prod].yml and .env sitting
#      next to it.
#
# A config file is shell-syntax KEY=VALUE pairs (no executable logic). It
# must NEVER contain real secrets — those live in WV_ENV_FILE.
#
# Recognised keys:
#   WV_COMPOSE_FILE            absolute path to the compose file
#   WV_COMPOSE_OVERRIDE_FILE   absolute path to an optional override file
#   WV_ENV_FILE                absolute path to the env file (.env)
#   WV_BACKUPS_DIR             absolute path to the backups directory
#   WV_COMPOSE_PROJECT         docker compose project name (default: wealthview)
#   WV_HOST                    user@host for remote operation (sets
#                              DOCKER_HOST=ssh://...); requires ssh-agent
#                              with a key authorised on the remote.
#   WV_APP_PORT                host port the app is bound to (default 80)
#   WV_HEALTH_URL              full health URL; overrides
#                              http://localhost:$WV_APP_PORT/actuator/health
#   WV_PREVIOUS_IMAGE_FILE     where update/rollback persist the prior image
#                              tag (default: $WV_ROOT/.wv-previous-image)

WV_CONFIG_SEARCH_PATHS=(
    "/etc/wealthview/wv.conf"
)
if [[ -n "${XDG_CONFIG_HOME:-}" ]]; then
    WV_CONFIG_SEARCH_PATHS+=("$XDG_CONFIG_HOME/wealthview/wv.conf")
fi
WV_CONFIG_SEARCH_PATHS+=("$HOME/.config/wealthview/wv.conf")

wv_config_resolved_path() {
    # Echoes the path of the config file that was loaded, or "(none)" if
    # we fell back to source-tree mode. Used by config-check and help.
    echo "${WV_CONFIG_RESOLVED:-(none)}"
}

_wv_load_config_file() {
    local path="$1"
    [[ -f "$path" ]] || return 1
    # Safety: we source the file directly, so it must be operator-owned
    # and trusted. We restrict acceptable line shapes via a syntax sniff
    # before sourcing so an accidentally executable .conf can't silently
    # run subprocesses on us.
    local line
    while IFS= read -r line; do
        # Allow blank lines, comments, and KEY=VALUE / KEY="VALUE" / KEY='VALUE'.
        [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
        if ! [[ "$line" =~ ^[[:space:]]*[A-Z_][A-Z0-9_]*=.*$ ]]; then
            wv_die "Refusing to source $path: unexpected line ('${line:0:60}'). Use KEY=VALUE only."
        fi
    done < "$path"
    # shellcheck disable=SC1090
    . "$path"
    export WV_CONFIG_RESOLVED="$path"
}

_wv_resolve_config() {
    # If WV_CONFIG_FILE was set explicitly (via --config or env), use it.
    if [[ -n "${WV_CONFIG_FILE:-}" ]]; then
        [[ -f "$WV_CONFIG_FILE" ]] || wv_die "WV_CONFIG_FILE points at $WV_CONFIG_FILE but it doesn't exist."
        _wv_load_config_file "$WV_CONFIG_FILE"
        return
    fi
    # Test escape hatch: WV_DISABLE_CONFIG_SEARCH=1 forces source-tree mode
    # and skips the /etc and ~/.config lookups. Used by the bats suite so
    # a real config file on the developer's machine doesn't bleed in.
    if [[ "${WV_DISABLE_CONFIG_SEARCH:-0}" != "1" ]]; then
        local candidate
        for candidate in "${WV_CONFIG_SEARCH_PATHS[@]}"; do
            if [[ -f "$candidate" ]]; then
                _wv_load_config_file "$candidate"
                return
            fi
        done
    fi
    # Source-tree fallback — only if it actually looks like a checkout.
    if [[ -f "$WV_ROOT/docker-compose.yml" ]]; then
        export WV_CONFIG_RESOLVED="(source tree: $WV_ROOT)"
        return
    fi
    wv_die "No wv.conf found (searched: ${WV_CONFIG_SEARCH_PATHS[*]}) and $WV_ROOT is not a source tree. Create /etc/wealthview/wv.conf or pass --config /path/to/wv.conf."
}

_wv_resolve_config

# Apply defaults for anything the config file (or env) didn't already set.
# These match the historical source-tree layout so behaviour is unchanged
# when wv is invoked from the repo.
: "${WV_ENV_FILE:=$WV_ROOT/.env}"
: "${WV_BACKUPS_DIR:=$WV_ROOT/backups}"
: "${WV_COMPOSE_PROJECT:=wealthview}"
: "${WV_APP_PORT:=80}"
: "${WV_PREVIOUS_IMAGE_FILE:=$WV_ROOT/.wv-previous-image}"
export WV_ENV_FILE WV_BACKUPS_DIR WV_COMPOSE_PROJECT WV_APP_PORT WV_PREVIOUS_IMAGE_FILE

# --- Environment / mode detection. -----------------------------------------
# Returns "prod" if WEALTHVIEW_VERSION is set in the resolved env file (real
# deploy), otherwise "dev". Reads the env file without sourcing it (which
# could leak vars or run side effects).
wv_env_file() {
    printf '%s\n' "$WV_ENV_FILE"
}

wv_env_get() {
    # wv_env_get VAR_NAME -> prints the raw value (or empty), no exit code logic.
    local var="$1"
    local file
    file="$(wv_env_file)"
    [[ -f "$file" ]] || return 0
    # Match VAR=... at start of line; strip surrounding quotes.
    sed -n -E "s/^${var}=([\"']?)([^\"'#]*)\\1[[:space:]]*$/\2/p" "$file" | tail -n 1
}

wv_mode() {
    # If the user pinned WEALTHVIEW_VERSION, treat the deployment as prod.
    if [[ -n "$(wv_env_get WEALTHVIEW_VERSION)" ]]; then
        echo "prod"
    else
        echo "dev"
    fi
}

wv_compose_file() {
    # Explicit config wins.
    if [[ -n "${WV_COMPOSE_FILE:-}" ]]; then
        echo "$WV_COMPOSE_FILE"
        return
    fi
    # Source-tree fallback: dev vs prod compose by mode.
    case "$(wv_mode)" in
        prod) echo "$WV_ROOT/docker-compose.prod.yml" ;;
        *)    echo "$WV_ROOT/docker-compose.yml" ;;
    esac
}

wv_compose_override_file() {
    # Explicit config wins; empty means "no override".
    if [[ -n "${WV_COMPOSE_OVERRIDE_FILE:-}" ]]; then
        echo "$WV_COMPOSE_OVERRIDE_FILE"
        return
    fi
    case "$(wv_mode)" in
        prod) echo "$WV_ROOT/docker-compose.prod.override.yml" ;;
        *)    echo "$WV_ROOT/docker-compose.override.yml" ;;
    esac
}

# --- Compose wrapper. -------------------------------------------------------
# All subcommands MUST go through this so the same compose file is used
# everywhere, the project name is consistent, and remote-host operation
# (WV_HOST -> DOCKER_HOST=ssh://...) is centralised in one place.
wv_compose() {
    wv_require docker
    local primary
    primary="$(wv_compose_file)"
    [[ -f "$primary" ]] || wv_die "Compose file not found: $primary"

    local override
    override="$(wv_compose_override_file)"

    local -a docker_args=(compose -p "$WV_COMPOSE_PROJECT" -f "$primary")
    if [[ -n "$override" && -f "$override" ]]; then
        docker_args+=(-f "$override")
    fi

    # Remote operation. When WV_HOST is set we drive docker over SSH using
    # the standard DOCKER_HOST=ssh:// transport. The user needs ssh-agent
    # forwarding and a key authorised on the remote.
    if [[ -n "${WV_HOST:-}" ]]; then
        DOCKER_HOST="ssh://${WV_HOST}" docker "${docker_args[@]}" "$@"
    else
        docker "${docker_args[@]}" "$@"
    fi
}

# --- .env validation. -------------------------------------------------------
# REQUIRED_ENV_VARS: vars that MUST be set to a non-empty, non-placeholder
# value before the stack can run. Pre-update / first-up will refuse otherwise.
WV_REQUIRED_ENV_VARS=(DB_PASSWORD JWT_SECRET SUPER_ADMIN_PASSWORD MFA_ENCRYPTION_KEY)

# Placeholder values that should never appear in a deployed .env.
WV_FORBIDDEN_ENV_VALUES=(CHANGE_ME)

wv_env_check() {
    local file missing=() forbidden=()
    file="$(wv_env_file)"
    if [[ ! -f "$file" ]]; then
        wv_err "No env file at $file. Copy .env.example and fill in values, then retry."
        return 1
    fi
    local var val
    for var in "${WV_REQUIRED_ENV_VARS[@]}"; do
        val="$(wv_env_get "$var")"
        if [[ -z "$val" ]]; then
            missing+=("$var")
            continue
        fi
        local bad
        for bad in "${WV_FORBIDDEN_ENV_VALUES[@]}"; do
            if [[ "$val" == "$bad" ]]; then
                forbidden+=("$var=$bad")
            fi
        done
    done
    if (( ${#missing[@]} > 0 )); then
        wv_err "Missing required env var(s) in $file: ${missing[*]}"
    fi
    if (( ${#forbidden[@]} > 0 )); then
        wv_err "Placeholder value(s) still in $file: ${forbidden[*]}"
    fi
    if (( ${#missing[@]} + ${#forbidden[@]} > 0 )); then
        return 1
    fi
}

# --- File / directory helpers. ---------------------------------------------
wv_backups_dir() {
    printf '%s\n' "$WV_BACKUPS_DIR"
}

wv_timestamp() {
    date -u +%Y-%m-%dT%H-%M-%SZ
}

# --- Confirmation prompt. ---------------------------------------------------
# wv_confirm "Prompt text" — returns 0 on y/Y, 1 otherwise. Honours
# WV_ASSUME_YES=1 for non-interactive callers / scripted use.
wv_confirm() {
    local prompt="$1"
    if [[ "${WV_ASSUME_YES:-0}" == "1" ]]; then
        wv_log "WV_ASSUME_YES=1; auto-confirming: $prompt"
        return 0
    fi
    local reply
    read -r -p "$prompt [y/N] " reply
    [[ "$reply" == "y" || "$reply" == "Y" ]]
}

# --- Health check. ----------------------------------------------------------
# Polls /actuator/health on the configured host/port for up to N seconds.
# When operating remotely, the URL points at $WV_HOST's hostname (or
# WV_HEALTH_URL if explicitly set).
wv_wait_healthy() {
    local timeout_sec="${1:-90}"
    local url="${WV_HEALTH_URL:-}"
    if [[ -z "$url" ]]; then
        local port
        port="$(wv_env_get APP_PORT)"
        port="${port:-$WV_APP_PORT}"
        local host="localhost"
        if [[ -n "${WV_HOST:-}" ]]; then
            host="${WV_HOST##*@}"
        fi
        url="http://${host}:${port}/actuator/health"
    fi
    local elapsed=0
    local interval=3
    wv_log "Waiting up to ${timeout_sec}s for ${url}..."
    while (( elapsed < timeout_sec )); do
        if curl -fsS -o /dev/null --max-time 5 "$url"; then
            wv_log "Health check passed after ${elapsed}s."
            return 0
        fi
        sleep "$interval"
        elapsed=$((elapsed + interval))
    done
    wv_err "Health check did not pass within ${timeout_sec}s."
    return 1
}
