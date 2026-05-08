#!/usr/bin/env bash
# One-shot installer that points git at the in-repo .githooks directory.
# Re-run safely; idempotent.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true

if ! command -v gitleaks >/dev/null 2>&1; then
    cat >&2 <<EOF
WARNING: gitleaks is not on PATH. The pre-commit hook will block commits
until you install it. See .githooks/pre-commit for install hints.
EOF
    exit 0
fi

echo "Hooks installed. core.hooksPath -> .githooks"
echo "Pre-commit gitleaks scan: $(gitleaks version 2>&1)"
