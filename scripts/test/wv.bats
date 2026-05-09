#!/usr/bin/env bats
# Bats tests for the ./wv admin entry point.
#
# Most tests run without Docker. Tests that require Docker (backup, verify,
# restore round-trip) skip themselves when docker is unavailable, so this
# suite is safe to run in CI on a Docker-less runner.

setup() {
    BATS_TEST_TMPDIR="${BATS_TEST_TMPDIR:-$(mktemp -d)}"
    REPO_ROOT="$(cd "$(dirname "$BATS_TEST_FILENAME")/../.." && pwd)"
    export REPO_ROOT
    # Some tests mutate .env; do them in a sandboxed copy of the repo.
    SANDBOX="$BATS_TEST_TMPDIR/repo"
    mkdir -p "$SANDBOX"
    # Symlink the source tree but copy the files we'll mutate so tests don't
    # touch the real repo.
    cp -r "$REPO_ROOT/wv" "$SANDBOX/wv"
    cp -r "$REPO_ROOT/scripts" "$SANDBOX/scripts"
    cp -r "$REPO_ROOT/docker-compose.yml" "$SANDBOX/docker-compose.yml"
    cp -r "$REPO_ROOT/docker-compose.prod.yml" "$SANDBOX/docker-compose.prod.yml"
    cp "$REPO_ROOT/.env.example" "$SANDBOX/.env.example"
    [[ -d "$REPO_ROOT/infra" ]] && cp -r "$REPO_ROOT/infra" "$SANDBOX/infra"
    chmod +x "$SANDBOX/wv"
    cd "$SANDBOX"
    export WV_ASSUME_YES=1
}

# --- Top-level CLI surface ----------------------------------------------------

@test "help: prints usage and lists subcommands" {
    run ./wv help
    [ "$status" -eq 0 ]
    [[ "$output" == *"Subcommands"* ]]
    [[ "$output" == *"backup"* ]]
    [[ "$output" == *"restore"* ]]
    [[ "$output" == *"update"* ]]
    [[ "$output" == *"rollback"* ]]
    [[ "$output" == *"migrate-out"* ]]
    [[ "$output" == *"migrate-in"* ]]
    [[ "$output" == *"rotate-secret"* ]]
    [[ "$output" == *"config-check"* ]]
}

@test "no args: defaults to help" {
    run ./wv
    [ "$status" -eq 0 ]
    [[ "$output" == *"Subcommands"* ]]
}

@test "unknown subcommand: exits non-zero with hint" {
    run ./wv totally-not-real
    [ "$status" -eq 2 ]
    [[ "$output" == *"Unknown subcommand"* ]]
}

@test "each subcommand supports --help" {
    for sub in up down status logs psql backup backups restore verify update rollback migrate-out migrate-in rotate-secret config-check; do
        run ./wv "$sub" --help
        [ "$status" -eq 0 ] || {
            echo "FAILED: ./wv $sub --help (status $status)"
            echo "$output"
            return 1
        }
        [[ "$output" == *"$sub"* ]] || {
            echo "FAILED: ./wv $sub --help did not mention '$sub' in its help text"
            echo "$output"
            return 1
        }
    done
}

# --- config-check: env validation ---------------------------------------------

@test "config-check: fails when .env is missing" {
    rm -f .env
    run ./wv config-check
    [ "$status" -ne 0 ]
    [[ "$output" == *"No .env file"* ]]
}

@test "config-check: fails when required vars are placeholder CHANGE_ME" {
    cp .env.example .env
    run ./wv config-check
    [ "$status" -ne 0 ]
    [[ "$output" == *"Placeholder value"* ]]
}

@test "config-check: passes with all required vars set" {
    cat > .env <<EOF
DB_PASSWORD=test_pw_value
JWT_SECRET=test_jwt_secret_value_at_least_32_chars_long_xx
SUPER_ADMIN_PASSWORD=test_admin_pw
EOF
    run ./wv config-check
    [ "$status" -eq 0 ]
    [[ "$output" == *"OK"* ]]
}

# --- mode detection -----------------------------------------------------------

@test "mode: defaults to dev when WEALTHVIEW_VERSION is not set" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
EOF
    run ./wv status
    # Status exits non-zero because docker may not be running, but it should
    # report dev mode in its log header before failing.
    [[ "$output" == *"Mode: dev"* ]]
}

@test "mode: switches to prod when WEALTHVIEW_VERSION is set" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
WEALTHVIEW_VERSION=1.2.3
EOF
    run ./wv status
    [[ "$output" == *"Mode: prod"* ]]
}

# --- rotate-secret: env file mutation -----------------------------------------

@test "rotate-secret: requires a NAME argument" {
    cat > .env <<EOF
DB_PASSWORD=oldpw
JWT_SECRET=oldjwt
SUPER_ADMIN_PASSWORD=oldadmin
EOF
    run ./wv rotate-secret
    [ "$status" -eq 2 ]
    [[ "$output" == *"NAME"* ]]
}

@test "rotate-secret: rejects unknown NAME" {
    cat > .env <<EOF
DB_PASSWORD=oldpw
JWT_SECRET=oldjwt
SUPER_ADMIN_PASSWORD=oldadmin
EOF
    run ./wv rotate-secret SOMETHING_ELSE
    [ "$status" -ne 0 ]
    [[ "$output" == *"Unknown secret"* ]]
}

@test "rotate-secret: --dry-run does not modify .env" {
    cat > .env <<EOF
DB_PASSWORD=oldpw
JWT_SECRET=oldjwt
SUPER_ADMIN_PASSWORD=oldadmin
EOF
    local before
    before="$(cat .env)"
    run ./wv rotate-secret JWT_SECRET --dry-run
    [ "$status" -eq 0 ]
    [ "$(cat .env)" = "$before" ]
}

@test "rotate-secret JWT_SECRET --dry-run: reports the new length" {
    cat > .env <<EOF
DB_PASSWORD=oldpw
JWT_SECRET=oldjwt
SUPER_ADMIN_PASSWORD=oldadmin
EOF
    run ./wv rotate-secret JWT_SECRET --dry-run
    [ "$status" -eq 0 ]
    [[ "$output" == *"length"* ]]
}

# --- backup: dry run ---------------------------------------------------------

@test "backup: --dry-run prints intended actions and creates no file" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
EOF
    rm -rf backups
    run ./wv backup --dry-run
    [ "$status" -eq 0 ]
    [[ "$output" == *"dry-run"* ]]
    [[ "$output" == *"Would dump"* ]]
    [ ! -d backups ] || [ -z "$(ls -A backups 2>/dev/null)" ]
}

@test "backup: rejects --label with invalid characters" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
EOF
    run ./wv backup --dry-run --label "bad label with spaces"
    [ "$status" -ne 0 ]
    [[ "$output" == *"letters"* ]] || [[ "$output" == *"label"* ]]
}

# --- backups: listing without files -----------------------------------------

@test "backups: handles empty backups dir" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
EOF
    rm -rf backups
    run ./wv backups
    [ "$status" -eq 0 ]
    [[ "$output" == *"No backups"* ]]
}

@test "backups: lists pre-existing files" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
EOF
    mkdir -p backups
    : > backups/wealthview_2026-05-09T12-00-00Z.dump
    : > backups/wealthview_2026-05-08T12-00-00Z.dump.age
    run ./wv backups
    [ "$status" -eq 0 ]
    [[ "$output" == *"wealthview_2026-05-09T12-00-00Z.dump"* ]]
    [[ "$output" == *"wealthview_2026-05-08T12-00-00Z.dump.age"* ]]
}

# --- backup encryption round-trip (requires age) ----------------------------

@test "backup encrypt: round-trips through age (requires age + docker)" {
    if ! command -v age >/dev/null 2>&1; then
        skip "age not installed"
    fi
    if ! command -v docker >/dev/null 2>&1; then
        skip "docker not installed"
    fi
    if ! docker ps >/dev/null 2>&1; then
        skip "docker daemon not reachable"
    fi
    if ! docker compose -f docker-compose.yml ps --status running --services 2>/dev/null | grep -qx db; then
        skip "wealthview db is not running; this test exercises live backup"
    fi
    # Generate a throwaway age identity for this test.
    local key="$BATS_TEST_TMPDIR/test.key"
    age-keygen -o "$key" 2>/dev/null
    local recipient
    recipient="$(grep -E '^# public key:' "$key" | sed 's/^# public key: //')"
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
BACKUP_ENCRYPTION_RECIPIENT=$recipient
BACKUP_ENCRYPTION_KEY_FILE=$key
EOF
    rm -rf backups
    run ./wv backup --encrypt
    [ "$status" -eq 0 ]
    # Final line should be the path of the encrypted backup.
    local out_path
    out_path="$(echo "$output" | tail -n 1)"
    [[ "$out_path" == *.dump.age ]]
    [ -f "$out_path" ]
    # Round-trip decrypt to confirm the payload is recoverable.
    age -d -i "$key" -o "$BATS_TEST_TMPDIR/decrypted.dump" "$out_path"
    [ -s "$BATS_TEST_TMPDIR/decrypted.dump" ]
}
