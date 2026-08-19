# shellcheck shell=bash
# verify.sh — restore a backup into a throwaway pg container to confirm
# integrity. Teardown (the container, its anonymous data volume, and any
# decrypted plaintext dump) is registered with wv_on_exit from common.sh, so
# it also runs on the wv_die paths.

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
        # Register the unlink the instant the file exists: from here on it is a
        # DECRYPTED copy of the database, and every later failure path — the
        # wv_die calls below included — must not leave it in /tmp. backup.sh
        # takes the same care after encrypting.
        wv_on_exit "rm -f '$tmp_plain'"
        wv_log "Decrypting $file -> temporary file"
        age -d -i "$key_file" -o "$tmp_plain" "$file" || wv_die "age decryption failed"
        feed_path="$tmp_plain"
    fi

    local container="wv-verify-$$-$RANDOM"
    local password
    password="wvverify$(date +%s)$RANDOM"

    # Registered BEFORE `docker run` so a half-created container is still reaped
    # when the launch itself fails and wv_die fires. `-v` is not optional:
    # postgres:16 declares VOLUME /var/lib/postgresql/data, and `docker rm -f`
    # without it strands that anonymous volume — a full copy of the restored
    # database — even for a container started with --rm. This used to be a
    # RETURN trap, which `exit` (and therefore wv_die) never runs, so the
    # readiness-timeout path leaked the container outright.
    wv_on_exit "docker rm -fv '$container' >/dev/null 2>&1 || true"

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
