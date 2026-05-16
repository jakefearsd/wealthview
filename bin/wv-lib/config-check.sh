# shellcheck shell=bash
# config-check.sh — sanity-check .env, compose files, and tool availability.

wv_config_check() {
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv config-check — validate .env, compose files, and tool availability.

Reports:
  - Missing or placeholder env vars
  - Missing required CLI tools
  - Compose-file syntax errors
  - Optional: gitleaks scan of staged changes (if gitleaks is installed)

Exits non-zero if any required check fails.
USAGE
                return 0
                ;;
            *) wv_die "Unknown option for 'config-check': $1" ;;
        esac
    done

    local rc=0

    wv_log "Resolved configuration:"
    printf '  config file : %s\n' "$(wv_config_resolved_path)"
    printf '  env file    : %s\n' "$WV_ENV_FILE"
    printf '  compose     : %s\n' "$(wv_compose_file)"
    local override
    override="$(wv_compose_override_file)"
    if [[ -n "$override" && -f "$override" ]]; then
        printf '  override    : %s\n' "$override"
    fi
    printf '  backups dir : %s\n' "$WV_BACKUPS_DIR"
    printf '  project     : %s\n' "$WV_COMPOSE_PROJECT"
    if [[ -n "${WV_HOST:-}" ]]; then
        printf '  remote host : %s (DOCKER_HOST=ssh://%s)\n' "$WV_HOST" "$WV_HOST"
    else
        printf '  remote host : (local)\n'
    fi

    wv_log "Required tools:"
    local tool
    for tool in docker curl python3 openssl; do
        if command -v "$tool" >/dev/null 2>&1; then
            printf '  ok   %s\n' "$tool"
        else
            printf '  MISS %s\n' "$tool"
            rc=1
        fi
    done

    wv_log "Optional tools:"
    for tool in age age-keygen gitleaks rsync aws; do
        if command -v "$tool" >/dev/null 2>&1; then
            printf '  ok   %s\n' "$tool"
        else
            printf '  -    %s (optional)\n' "$tool"
        fi
    done

    wv_log ".env validation:"
    if wv_env_check; then
        printf '  ok   required vars present and non-placeholder\n'
    else
        rc=1
    fi

    wv_log "Compose file validation:"
    local file
    file="$(wv_compose_file)"
    if [[ ! -f "$file" ]]; then
        printf '  MISS %s\n' "$file"
        rc=1
    elif wv_compose config -q >/dev/null 2>&1; then
        printf '  ok   %s parses cleanly\n' "$file"
    else
        printf '  FAIL %s does not parse:\n' "$file"
        wv_compose config -q 2>&1 | sed 's/^/         /' || true
        rc=1
    fi

    if command -v gitleaks >/dev/null 2>&1; then
        wv_log "gitleaks (staged diff):"
        if gitleaks protect --staged --redact --no-banner \
            --config "$WV_ROOT/.gitleaks.toml" >/dev/null 2>&1; then
            printf '  ok   no secrets in staged diff\n'
        else
            printf '  WARN gitleaks reported issues; run: gitleaks protect --staged --redact\n'
        fi
    fi

    if (( rc == 0 )); then
        wv_log "config-check OK"
    else
        wv_err "config-check FAILED"
    fi
    return "$rc"
}
