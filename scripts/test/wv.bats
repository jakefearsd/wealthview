#!/usr/bin/env bats
# Bats tests for the ./wv admin entry point.
#
# Most tests run without Docker. Tests that require Docker (backup, verify,
# restore round-trip) skip themselves when docker is unavailable, so this
# suite is safe to run in CI on a Docker-less runner.

setup() {
    # bats owns BATS_TEST_TMPDIR and removes it after each test. Do NOT fall back
    # to a bare `mktemp -d` when it is unset: this file has no teardown(), so a
    # self-made directory would leak a full sandbox copy of the repo per test.
    # Fail loudly instead — an unset value means an unsupported bats.
    [[ -n "${BATS_TEST_TMPDIR:-}" ]] || {
        echo "BATS_TEST_TMPDIR is unset; bats >= 1.x is required" >&2
        return 1
    }
    REPO_ROOT="$(cd "$(dirname "$BATS_TEST_FILENAME")/../.." && pwd)"
    export REPO_ROOT
    # Some tests mutate .env; do them in a sandboxed copy of the repo.
    SANDBOX="$BATS_TEST_TMPDIR/repo"
    mkdir -p "$SANDBOX"
    # Symlink the source tree but copy the files we'll mutate so tests don't
    # touch the real repo. wv now lives at bin/wv with libraries under
    # bin/wv-lib/; ./wv at the repo root is a thin shim that execs bin/wv.
    cp -r "$REPO_ROOT/wv" "$SANDBOX/wv"
    cp -r "$REPO_ROOT/bin" "$SANDBOX/bin"
    cp -r "$REPO_ROOT/scripts" "$SANDBOX/scripts"
    cp -r "$REPO_ROOT/docker-compose.yml" "$SANDBOX/docker-compose.yml"
    cp -r "$REPO_ROOT/docker-compose.prod.yml" "$SANDBOX/docker-compose.prod.yml"
    cp "$REPO_ROOT/.env.example" "$SANDBOX/.env.example"
    [[ -d "$REPO_ROOT/infra" ]] && cp -r "$REPO_ROOT/infra" "$SANDBOX/infra"
    chmod +x "$SANDBOX/wv" "$SANDBOX/bin/wv"
    cd "$SANDBOX"
    export WV_ASSUME_YES=1
    # Make sure no real /etc/wealthview/wv.conf or ~/.config/wealthview/wv.conf
    # leaks into the sandbox; tests assume source-tree resolution.
    unset WV_CONFIG_FILE WV_HOST
    export WV_DISABLE_CONFIG_SEARCH=1
}

# --- Top-level CLI surface ----------------------------------------------------

@test "help: prints usage and lists subcommands" {
    run ./wv help
    [ "$status" -eq 0 ]
    [[ "$output" == *"SUBCOMMANDS"* ]]
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
    [[ "$output" == *"SUBCOMMANDS"* ]]
}

@test "unknown subcommand: exits non-zero with hint" {
    run ./wv totally-not-real
    [ "$status" -eq 2 ]
    [[ "$output" == *"Unknown subcommand"* ]]
}

@test "list-backups is an alias for backups" {
    # The dispatcher rewrites list-backups -> backups before sourcing the
    # subcommand file, so this should produce identical output to ./wv backups.
    rm -rf backups
    mkdir -p backups
    run ./wv list-backups
    [ "$status" -eq 0 ]
    [[ "$output" == *"No backups in"* ]]
}

@test "--config FILE: explicit config overrides source-tree defaults" {
    mkdir -p custom/backups
    cp docker-compose.yml custom/docker-compose.yml
    cat > custom/wv.conf <<EOF
WV_COMPOSE_FILE=$PWD/custom/docker-compose.yml
WV_ENV_FILE=$PWD/.env
WV_BACKUPS_DIR=$PWD/custom/backups
EOF
    # WV_DISABLE_CONFIG_SEARCH is exported by setup; --config wins anyway.
    unset WV_DISABLE_CONFIG_SEARCH
    run ./wv --config "$PWD/custom/wv.conf" config-check
    [ "$status" -eq 0 ] || {
        echo "config-check failed with explicit --config; output:"
        echo "$output"
    }
    [[ "$output" == *"custom/wv.conf"* ]]
    [[ "$output" == *"custom/backups"* ]]
}

@test "--config FILE: refuses a non-existent path" {
    run ./wv --config /tmp/does-not-exist-$RANDOM.conf status
    [ "$status" -eq 1 ]
    [[ "$output" == *"doesn't exist"* ]]
}

@test "config-file syntax sniff: rejects lines that look like code" {
    cat > bad.conf <<'EOF'
WV_BACKUPS_DIR=/tmp/x
echo "this is not a config line"
EOF
    run ./wv --config "$PWD/bad.conf" status
    [ "$status" -eq 1 ]
    [[ "$output" == *"Refusing to source"* ]]
}

@test "each subcommand supports --help" {
    for sub in up down restart status logs psql backup backups restore verify update rollback migrate-out migrate-in rotate-secret config-check prune; do
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
    [[ "$output" == *"No env file"* ]]
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
MFA_ENCRYPTION_KEY=test_mfa_key_value_at_least_32_chars_long_yyy
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

@test "backup: --dry-run in dev mode does not warn about plaintext backups" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
EOF
    rm -rf backups
    run ./wv backup --dry-run
    [ "$status" -eq 0 ]
    [[ "$output" != *"WARN"* ]]
}

@test "backup: --dry-run in prod mode without --encrypt warns about plaintext backups" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
MFA_ENCRYPTION_KEY=w
WEALTHVIEW_VERSION=1.0.0
EOF
    rm -rf backups
    run ./wv backup --dry-run
    [ "$status" -eq 0 ]
    [[ "$output" == *"WARN"* ]]
    [[ "$output" == *"unencrypted"* ]] || [[ "$output" == *"plaintext"* ]]
}

@test "backup: --dry-run in prod mode with --encrypt does not warn" {
    cat > .env <<EOF
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
MFA_ENCRYPTION_KEY=w
WEALTHVIEW_VERSION=1.0.0
EOF
    rm -rf backups
    run ./wv backup --dry-run --encrypt
    [ "$status" -eq 0 ]
    [[ "$output" != *"WARN"* ]]
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

# --- Release image handling ---------------------------------------------------
#
# The prod compose file resolves the app image from a registry
# (ghcr.io/<owner>/wealthview:<version>) rather than building locally. Rollback
# recovers by re-pinning the tag of the previously running image, so it has to
# parse a fully-qualified registry reference — not just `wealthview:<tag>`.

_load_update_lib() {
    # shellcheck disable=SC1090
    WV_LIB_DIR="$SANDBOX/bin/wv-lib"
    . "$SANDBOX/bin/wv-lib/update.sh"
}

@test "image tag: extracts the tag from a GHCR-qualified reference" {
    _load_update_lib
    run _wv_image_tag "ghcr.io/jakefearsd/wealthview:1.2.5"
    [ "$status" -eq 0 ]
    [ "$output" = "1.2.5" ]
}

@test "image tag: extracts the tag from a bare name:tag reference" {
    _load_update_lib
    run _wv_image_tag "wealthview:1.2.4"
    [ "$status" -eq 0 ]
    [ "$output" = "1.2.4" ]
}

@test "image tag: handles a registry host that carries a port" {
    _load_update_lib
    run _wv_image_tag "registry.internal:5000/wealthview:1.2.5"
    [ "$status" -eq 0 ]
    [ "$output" = "1.2.5" ]
}

@test "image tag: rejects an untagged reference rather than guessing" {
    _load_update_lib
    # A host:port with no tag must not be mistaken for name:tag — otherwise
    # rollback would re-pin the app to a version literally named "5000".
    run _wv_image_tag "registry.internal:5000/wealthview"
    [ "$status" -ne 0 ]
}

@test "image repo: strips the tag to leave the repository reference" {
    _load_update_lib
    run _wv_image_repo "ghcr.io/jakefearsd/wealthview:1.2.5"
    [ "$status" -eq 0 ]
    [ "$output" = "ghcr.io/jakefearsd/wealthview" ]
}

@test "update: defaults to pulling in prod mode" {
    cat > .env <<'EOF'
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
WEALTHVIEW_VERSION=1.2.5
EOF
    # common.sh derives its paths from WV_ROOT / WV_LIB_DIR, which the bin/wv
    # dispatcher normally sets before sourcing it.
    export WV_ROOT="$SANDBOX" WV_LIB_DIR="$SANDBOX/bin/wv-lib"
    # shellcheck disable=SC1090
    . "$SANDBOX/bin/wv-lib/common.sh"
    . "$SANDBOX/bin/wv-lib/update.sh"
    run _wv_update_default_acquire
    [ "$status" -eq 0 ]
    [ "$output" = "pull" ]
}

@test "update: defaults to building in dev mode" {
    # No WEALTHVIEW_VERSION -> dev mode -> the dev compose file builds from
    # source and has no image to pull.
    cat > .env <<'EOF'
DB_PASSWORD=x
JWT_SECRET=y
SUPER_ADMIN_PASSWORD=z
EOF
    # common.sh derives its paths from WV_ROOT / WV_LIB_DIR, which the bin/wv
    # dispatcher normally sets before sourcing it.
    export WV_ROOT="$SANDBOX" WV_LIB_DIR="$SANDBOX/bin/wv-lib"
    # shellcheck disable=SC1090
    . "$SANDBOX/bin/wv-lib/common.sh"
    . "$SANDBOX/bin/wv-lib/update.sh"
    run _wv_update_default_acquire
    [ "$status" -eq 0 ]
    [ "$output" = "build" ]
}

# --- Cleanup registry (wv_on_exit) --------------------------------------------
#
# bash has exactly ONE EXIT trap per shell, and bin/wv runs every subcommand in
# a single shell process, so subcommands that called `trap ... EXIT` themselves
# silently clobbered one another. The RETURN-trap workaround misfired in two
# further ways. Three production resource leaks came out of that, and each of
# these tests pins the semantics that makes one of them impossible:
#
#   wv verify      used a RETURN trap, but `exit` does not run RETURN traps, so
#                  a timed-out readiness probe left a live postgres container
#                  AND a decrypted database dump behind.
#   wv migrate-out set a RETURN trap and then sourced backup.sh; the trap fired
#                  when that sourced script completed and deleted migrate-out's
#                  own staging directory mid-run.
#   wv migrate-in  registered its staging dir with `trap ... EXIT`, which the
#                  trap wv_restore installed then replaced, leaking the
#                  extracted bundle on every encrypted restore.
#
# Do not delete these as redundant with each other: they cover different failure
# modes of the same one-trap-per-shell constraint.

_write_cleanup_script() {
    # Writes a standalone script at $1 that boots the real common.sh exactly the
    # way bin/wv does (WV_ROOT + WV_LIB_DIR, config search disabled, `set -euo
    # pipefail` already in effect), followed by the body read from stdin.
    #
    # These tests need a child process: the registry is about what a whole shell
    # does on its way out, and calling `exit` in a bats test body would just end
    # the test. Run the result with `run bash "$script"` and assert on $status,
    # $output, and file side effects under $BATS_TEST_TMPDIR.
    local path="$1"
    {
        echo 'set -euo pipefail'
        echo "export WV_ROOT='$SANDBOX' WV_LIB_DIR='$SANDBOX/bin/wv-lib'"
        echo 'export WV_DISABLE_CONFIG_SEARCH=1'
        echo ". '$SANDBOX/bin/wv-lib/common.sh'"
        cat
    } > "$path"
}

@test "cleanup registry: registered actions run when the shell exits normally" {
    local marker="$BATS_TEST_TMPDIR/ran"
    local script="$BATS_TEST_TMPDIR/normal-exit.sh"
    _write_cleanup_script "$script" <<EOF
wv_on_exit "touch '$marker'"
EOF
    [ ! -f "$marker" ]
    run bash "$script"
    [ "$status" -eq 0 ]
    [ -f "$marker" ]
}

@test "cleanup registry: actions run LIFO, most recently registered first" {
    # Innermost-first matters: an inner action often depends on an outer one
    # (stop the container before deleting the directory it bind-mounts).
    local script="$BATS_TEST_TMPDIR/lifo.sh"
    _write_cleanup_script "$script" <<'EOF'
wv_on_exit "echo outer"
wv_on_exit "echo inner"
EOF
    run bash "$script"
    [ "$status" -eq 0 ]
    [ "${lines[0]}" = "inner" ]
    [ "${lines[1]}" = "outer" ]
}

@test "cleanup registry: preserves the exit status it was invoked with" {
    local marker="$BATS_TEST_TMPDIR/ran"
    local script="$BATS_TEST_TMPDIR/exit-status.sh"
    _write_cleanup_script "$script" <<EOF
wv_on_exit "touch '$marker'"
exit 7
EOF
    run bash "$script"
    [ "$status" -eq 7 ]
    [ -f "$marker" ]
}

@test "cleanup registry: a wv_die path still exits 1 after cleanup has run" {
    # Cleanup must never launder a failure into a success — callers (and CI)
    # key off wv's exit code.
    local marker="$BATS_TEST_TMPDIR/ran"
    local script="$BATS_TEST_TMPDIR/die-status.sh"
    _write_cleanup_script "$script" <<EOF
wv_on_exit "touch '$marker'"
wv_die "something went wrong"
EOF
    run bash "$script"
    [ "$status" -eq 1 ]
    [[ "$output" == *"something went wrong"* ]]
    [ -f "$marker" ]
}

@test "cleanup registry: cleanup runs on the exit path (regression: wv verify)" {
    # wv verify used a RETURN trap; `exit` doesn't run those, so a timed-out
    # readiness probe left a live postgres container and a decrypted dump.
    local container="$BATS_TEST_TMPDIR/fake-container"
    local decrypted="$BATS_TEST_TMPDIR/decrypted.dump"
    local script="$BATS_TEST_TMPDIR/verify-timeout.sh"
    _write_cleanup_script "$script" <<EOF
_fake_verify() {
    touch '$container'
    wv_on_exit "rm -f '$container'"
    touch '$decrypted'
    wv_on_exit "rm -f '$decrypted'"
    wv_die "throwaway postgres did not become ready in time"
}
_fake_verify
EOF
    run bash "$script"
    [ "$status" -eq 1 ]
    [ ! -f "$container" ]
    [ ! -f "$decrypted" ]
}

@test "cleanup registry: a sourced script completing does not fire cleanup (regression: wv migrate-out)" {
    # migrate-out registered its staging dir, then sourced backup.sh. With a
    # RETURN trap the source's completion fired cleanup and deleted the staging
    # dir out from under the still-running command.
    local staging="$BATS_TEST_TMPDIR/staging"
    local inner="$BATS_TEST_TMPDIR/inner-lib.sh"
    local script="$BATS_TEST_TMPDIR/migrate-out.sh"
    mkdir -p "$staging"
    echo 'wv_log "inner library ran"' > "$inner"
    _write_cleanup_script "$script" <<EOF
wv_on_exit "rm -rf '$staging'"
. '$inner'
[[ -d '$staging' ]] || wv_die "staging dir was deleted by the sourced script"
wv_log "staging dir survived the sourced script"
EOF
    run bash "$script"
    [ "$status" -eq 0 ]
    [[ "$output" == *"staging dir survived the sourced script"* ]]
    # ...and it is still cleaned up once the shell itself exits.
    [ ! -d "$staging" ]
}

@test "cleanup registry: a second registration does not discard the first (regression: wv migrate-in)" {
    # migrate-in's staging-dir EXIT trap was silently replaced by the one
    # wv_restore installed, so the extracted bundle leaked on every encrypted
    # restore. Registering is additive; both actions must run.
    local outer="$BATS_TEST_TMPDIR/migrate-in-staging"
    local inner_dir="$BATS_TEST_TMPDIR/restore-staging"
    local inner="$BATS_TEST_TMPDIR/restore-lib.sh"
    local script="$BATS_TEST_TMPDIR/migrate-in.sh"
    mkdir -p "$outer" "$inner_dir"
    cat > "$inner" <<EOF
wv_on_exit "rm -rf '$inner_dir'"
EOF
    _write_cleanup_script "$script" <<EOF
wv_on_exit "rm -rf '$outer'"
. '$inner'
EOF
    run bash "$script"
    [ "$status" -eq 0 ]
    [ ! -d "$inner_dir" ]
    [ ! -d "$outer" ]
}

@test "cleanup registry: the handler is idempotent, so signal and EXIT cannot double-fire" {
    # The INT/TERM traps run cleanup eagerly and then exit, which fires EXIT
    # too. Running the handler twice must not run the actions twice.
    local log="$BATS_TEST_TMPDIR/cleanup.log"
    local script="$BATS_TEST_TMPDIR/idempotent.sh"
    _write_cleanup_script "$script" <<EOF
wv_on_exit "echo ran >> '$log'"
_wv_run_cleanup
_wv_run_cleanup
EOF
    run bash "$script"
    [ "$status" -eq 0 ]
    # Once for the explicit calls, and not again via the EXIT trap.
    [ "$(wc -l < "$log")" -eq 1 ]
}

@test "cleanup registry: one failing action does not stop the rest" {
    # Actions are isolated with `|| true`; without it `set -euo pipefail` would
    # abort the loop and skip every action registered before the failure.
    local marker="$BATS_TEST_TMPDIR/still-ran"
    local script="$BATS_TEST_TMPDIR/failing-action.sh"
    _write_cleanup_script "$script" <<EOF
wv_on_exit "touch '$marker'"
wv_on_exit "false"
EOF
    run bash "$script"
    [ "$status" -eq 0 ]
    [ -f "$marker" ]
}
