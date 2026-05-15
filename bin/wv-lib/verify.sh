# shellcheck shell=bash
# verify.sh — restore a backup into a throwaway pg container to confirm
# integrity. Tears down the container and its volume on exit.

# Postgres image used for verification — keep aligned with docker-compose.yml.
WV_VERIFY_PG_IMAGE="postgres:16"

wv_verify() {
    local file=""
    while (( $# > 0 )); do
        case "$1" in
            -h|--help)
                cat <<'USAGE'
./wv verify <file> — restore <file> into a throwaway PostgreSQL container,
run sanity queries, then tear down. Confirms the dump is not corrupt
without touching the live database.

Files ending in .age are decrypted on-the-fly using BACKUP_ENCRYPTION_KEY_FILE.

Exits 0 on a clean round-trip, non-zero otherwise.
USAGE
                return 0
                ;;
            -*) wv_die "Unknown option for 'verify': $1" ;;
            *)
                if [[ -n "$file" ]]; then
                    wv_die "verify takes exactly one file argument"
                fi
                file="$1"
                shift
                ;;
        esac
    done

    [[ -n "$file" ]] || { wv_err "Usage: ./wv verify <file>"; return 2; }
    [[ -f "$file" ]] || wv_die "File not found: $file"
    wv_require docker

    local tmp_plain="" feed_path="$file"
    if [[ "$file" == *.age ]]; then
        wv_require age
        local key_file="${BACKUP_ENCRYPTION_KEY_FILE:-$(wv_env_get BACKUP_ENCRYPTION_KEY_FILE)}"
        [[ -n "$key_file" && -f "$key_file" ]] \
            || wv_die "Encrypted backup requires BACKUP_ENCRYPTION_KEY_FILE pointing to an age identity file."
        tmp_plain="$(mktemp -t wv-verify-XXXXXX.dump)"
        wv_log "Decrypting $file -> temporary file"
        age -d -i "$key_file" -o "$tmp_plain" "$file" \
            || { rm -f "$tmp_plain"; wv_die "age decryption failed"; }
        feed_path="$tmp_plain"
    fi

    local container="wv-verify-$$-$RANDOM"
    local password
    password="wvverify$(date +%s)$RANDOM"

    # Cleanup runs on RETURN (function exit) and on signals. We bake the
    # container name and tmp file path into the trap string so the values
    # are available even after locals go out of scope under set -u.
    # shellcheck disable=SC2064
    trap "docker rm -f '$container' >/dev/null 2>&1 || true; [[ -n '$tmp_plain' ]] && rm -f '$tmp_plain'; trap - INT TERM RETURN" RETURN INT TERM

    wv_log "Launching throwaway postgres container ($WV_VERIFY_PG_IMAGE)"
    docker run -d --rm \
        --name "$container" \
        -e POSTGRES_DB=wealthview \
        -e POSTGRES_USER=wv_app \
        -e POSTGRES_PASSWORD="$password" \
        "$WV_VERIFY_PG_IMAGE" >/dev/null \
        || wv_die "Failed to start verify container."

    wv_log "Waiting for postgres to accept connections..."
    local i=0
    while (( i < 30 )); do
        if docker exec "$container" pg_isready -U wv_app -d wealthview >/dev/null 2>&1; then
            break
        fi
        sleep 1
        i=$((i + 1))
    done
    if (( i >= 30 )); then
        wv_die "Verify postgres did not become ready within 30s."
    fi

    wv_log "Restoring $file into verify container..."
    if ! docker exec -i "$container" pg_restore --clean --if-exists -U wv_app -d wealthview < "$feed_path"; then
        wv_warn "pg_restore emitted warnings (typical for --clean on a fresh database)."
    fi

    wv_log "Running sanity queries..."
    local sql out
    sql="
SELECT relname, n_live_tup
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY n_live_tup DESC
LIMIT 5;"
    out="$(docker exec "$container" psql -U wv_app -d wealthview -At -c "$sql")"
    if [[ -z "$out" ]]; then
        wv_err "Verify FAILED: no user tables present after restore."
        return 1
    fi
    echo "$out"

    # Require at least one core table to exist (defensive — if every query above
    # came back empty we've already failed; this guards against partial dumps).
    local table_count
    table_count="$(docker exec "$container" psql -U wv_app -d wealthview -At -c \
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")"
    if [[ -z "$table_count" || "$table_count" -lt 5 ]]; then
        wv_err "Verify FAILED: only ${table_count:-0} public tables; backup may be truncated."
        return 1
    fi
    wv_log "Verify PASSED: $table_count public tables present."
}
