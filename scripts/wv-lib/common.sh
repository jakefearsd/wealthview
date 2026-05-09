# shellcheck shell=bash
# common.sh — shared helpers loaded by every wv subcommand.
#
# This file is sourced by ./wv with `set -euo pipefail` already in effect.
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

# --- Environment / mode detection. -----------------------------------------
# Returns "prod" if WEALTHVIEW_VERSION is set in .env (real deploy),
# otherwise "dev". Reads .env without sourcing it (which could leak vars).
wv_env_file() {
    printf '%s/.env\n' "$WV_ROOT"
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
    case "$(wv_mode)" in
        prod) echo "$WV_ROOT/docker-compose.prod.yml" ;;
        *)    echo "$WV_ROOT/docker-compose.yml" ;;
    esac
}

# --- Compose wrapper. -------------------------------------------------------
# All subcommands MUST go through this so the same compose file is used
# everywhere and so future flags (e.g. project name) live in one place.
#
# An adjacent docker-compose.override.yml is merged automatically (compose's
# default behavior). docker-compose.prod.override.yml is honoured in prod
# mode using the same convention, since `-f` disables the implicit override
# lookup.
wv_compose() {
    wv_require docker
    local primary
    primary="$(wv_compose_file)"
    [[ -f "$primary" ]] || wv_die "Compose file not found: $primary"

    local override=""
    case "$(wv_mode)" in
        prod) override="$WV_ROOT/docker-compose.prod.override.yml" ;;
        *)    override="$WV_ROOT/docker-compose.override.yml" ;;
    esac

    if [[ -f "$override" ]]; then
        docker compose -f "$primary" -f "$override" "$@"
    else
        docker compose -f "$primary" "$@"
    fi
}

# --- .env validation. -------------------------------------------------------
# REQUIRED_ENV_VARS: vars that MUST be set to a non-empty, non-placeholder
# value before the stack can run. Pre-update / first-up will refuse otherwise.
WV_REQUIRED_ENV_VARS=(DB_PASSWORD JWT_SECRET SUPER_ADMIN_PASSWORD)

# Placeholder values that should never appear in a deployed .env.
WV_FORBIDDEN_ENV_VALUES=(CHANGE_ME)

wv_env_check() {
    local file missing=() forbidden=()
    file="$(wv_env_file)"
    if [[ ! -f "$file" ]]; then
        wv_err "No .env file at $file. Copy .env.example and fill in values, then retry."
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
    printf '%s/backups\n' "$WV_ROOT"
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
# Polls /actuator/health on the host port for up to N seconds.
wv_wait_healthy() {
    local timeout_sec="${1:-90}"
    local port
    port="$(wv_env_get APP_PORT)"
    port="${port:-80}"
    local url="http://localhost:${port}/actuator/health"
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
