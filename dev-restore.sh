#!/usr/bin/env bash
# DEPRECATED: thin shim that delegates to ./wv restore.
#
# Kept so existing muscle memory (and any docs that haven't been updated)
# continues to work. New scripts should call ./wv restore directly. See
# docs/deployment/operations.md.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

# Old behavior: with no args, list backups. wv restore mirrors that — call
# wv backups first if no path given so the deprecation doesn't break the
# ergonomics of `./dev-restore.sh` -> "show me what I have".
if (( $# == 0 )); then
    exec "$SCRIPT_DIR/wv" backups
fi

exec "$SCRIPT_DIR/wv" restore "$@"
