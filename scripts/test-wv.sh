#!/usr/bin/env bash
# test-wv.sh — convenience runner for the wv bats suite + shellcheck.
#
# Usage:
#   ./scripts/test-wv.sh             # run shellcheck + bats
#   ./scripts/test-wv.sh shellcheck  # just shellcheck
#   ./scripts/test-wv.sh bats        # just bats

set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
cd "$ROOT"

target="${1:-all}"

run_shellcheck() {
    if ! command -v shellcheck >/dev/null 2>&1; then
        echo "shellcheck not installed; skipping" >&2
        return 0
    fi
    echo "==> shellcheck"
    # Find all .sh files plus the wv entry point.
    mapfile -t files < <(find . -type f \
        \( -name '*.sh' -o -name 'wv' \) \
        -not -path './node_modules/*' \
        -not -path './.worktrees/*' \
        -not -path './backend/*/target/*' \
        -not -path './frontend/dist/*' \
        -not -path './frontend/node_modules/*' \
        -not -path './.git/*' \
        | sort)
    # Severity threshold: only `warning` and above fail the build. Info/style
    # findings on older shims (deploy.sh, manual-test.sh) remain visible but
    # don't block CI. New scripts in scripts/wv-lib/ are clean even at
    # `--severity=style`.
    shellcheck -x --severity=warning "${files[@]}"
    echo "==> shellcheck OK (${#files[@]} files, severity=warning)"
}

run_bats() {
    if ! command -v bats >/dev/null 2>&1; then
        echo "bats not installed; skipping" >&2
        return 0
    fi
    echo "==> bats"
    bats scripts/test/
}

case "$target" in
    shellcheck) run_shellcheck ;;
    bats) run_bats ;;
    all) run_shellcheck; run_bats ;;
    *) echo "Usage: $0 [shellcheck|bats|all]" >&2; exit 2 ;;
esac
