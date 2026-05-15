# shellcheck shell=bash
# rotate-secret.sh — generate a new value for a known secret in .env and
# restart the relevant services in the right order.

wv_rotate_secret() {
    local name="" dry_run=0
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv rotate-secret <NAME> — generate and apply a new secret value in .env.

Supported NAME values:
    JWT_SECRET             48-char base64. Restart of the app picks it up.
                           Existing JWTs become invalid; users must log in again.
    SUPER_ADMIN_PASSWORD   24-char base64. Updated in .env. The running
                           database row is updated via psql so the new value
                           takes effect immediately.
    DB_PASSWORD            24-char base64. Updated in .env AND in PostgreSQL
                           via ALTER USER, then the app restarts.
                           NOTE: pgdata is initialised once with the original
                           password; this command keeps both in sync.

OPTIONS
    --dry-run              show what would change without writing .env.

After rotation, take a fresh backup so it covers the new state.
USAGE
                return 0
                ;;
            --dry-run) dry_run=1; shift ;;
            -*) wv_die "Unknown option for 'rotate-secret': $1" ;;
            *)
                if [[ -n "$name" ]]; then
                    wv_die "rotate-secret takes one NAME argument."
                fi
                name="$1"
                shift
                ;;
        esac
    done

    [[ -n "$name" ]] || { wv_err "Usage: ./wv rotate-secret <NAME>"; return 2; }

    case "$name" in
        JWT_SECRET|SUPER_ADMIN_PASSWORD|DB_PASSWORD) ;;
        *) wv_die "Unknown secret name: $name. Supported: JWT_SECRET, SUPER_ADMIN_PASSWORD, DB_PASSWORD." ;;
    esac

    wv_require openssl
    local new_value
    case "$name" in
        JWT_SECRET) new_value="$(openssl rand -base64 48 | tr -d '\n')" ;;
        SUPER_ADMIN_PASSWORD|DB_PASSWORD) new_value="$(openssl rand -base64 24 | tr -d '\n')" ;;
    esac

    local env_file
    env_file="$(wv_env_file)"
    [[ -f "$env_file" ]] || wv_die "$env_file does not exist."

    if (( dry_run == 1 )); then
        wv_log "[dry-run] Would set $name in $env_file (length: ${#new_value})"
        return 0
    fi

    _wv_env_set "$env_file" "$name" "$new_value"
    wv_log "Updated $name in $env_file."

    case "$name" in
        JWT_SECRET)
            wv_log "Restarting app for new JWT_SECRET to take effect..."
            wv_compose up -d --no-deps app
            wv_wait_healthy 90 || wv_warn "App did not pass health check after JWT rotation."
            wv_log "All existing JWTs are now invalid; users must log in again."
            ;;
        SUPER_ADMIN_PASSWORD)
            wv_log "Updating super admin password in database..."
            local hash
            # Use Spring's bcrypt encoder via openssl for the hashed form.
            # Spring Security stores users with $2a$ bcrypt; openssl can't
            # produce that, so go through python's bcrypt if available, else
            # rely on the app's idempotent SuperAdminInitializer at restart.
            if python3 -c "import bcrypt" 2>/dev/null; then
                hash="$(python3 -c "import bcrypt,sys; print(bcrypt.hashpw(sys.argv[1].encode(),bcrypt.gensalt()).decode())" "$new_value")"
                wv_compose exec -T db psql -U wv_app -d wealthview -c \
                    "UPDATE users SET password_hash='$hash' WHERE email='admin@wealthview.local';" \
                    || wv_warn "Failed to update password row directly; restarting app to let SuperAdminInitializer catch up."
            else
                wv_warn "python3 bcrypt not available; restarting app — SuperAdminInitializer will sync the new value at boot."
            fi
            wv_compose up -d --no-deps app
            wv_wait_healthy 90 || wv_warn "App did not pass health check after rotation."
            ;;
        DB_PASSWORD)
            wv_log "Updating database role password via ALTER USER..."
            wv_compose exec -T db psql -U wv_app -d postgres -c \
                "ALTER USER wv_app WITH PASSWORD '$new_value';" \
                || wv_die "ALTER USER failed; .env is updated but the live DB still has the old password."
            wv_log "Restarting app to pick up new DB_PASSWORD..."
            wv_compose up -d --no-deps app
            wv_wait_healthy 90 || wv_warn "App did not pass health check after DB password rotation."
            ;;
    esac

    wv_log "Take a fresh backup now: ./wv backup --label post-rotate-${name}"
}

# Internal: set NAME=VALUE in an .env-style file, preserving other lines.
# Adds the line if missing. Does not interpret the value (no escaping needed
# for single-line base64 strings, which is all we generate).
_wv_env_set() {
    local file="$1" var="$2" value="$3"
    local tmp
    tmp="$(mktemp)"
    if grep -qE "^${var}=" "$file"; then
        # Replace value while keeping the variable line position. Use awk to
        # avoid sed's headaches with arbitrary base64 (which can contain /).
        awk -v var="$var" -v val="$value" '
            BEGIN { changed=0 }
            $0 ~ "^"var"=" { print var"="val; changed=1; next }
            { print }
            END { if (!changed) print var"="val }
        ' "$file" > "$tmp"
    else
        cp "$file" "$tmp"
        printf '%s=%s\n' "$var" "$value" >> "$tmp"
    fi
    chmod --reference="$file" "$tmp" 2>/dev/null || chmod 600 "$tmp"
    mv "$tmp" "$file"
}
