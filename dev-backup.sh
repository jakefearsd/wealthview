#!/usr/bin/env bash
# DEPRECATED: thin shim that delegates to ./wv backup.
#
# Kept so existing muscle memory (and any docs that haven't been updated)
# continues to work. New scripts should call ./wv backup directly. See
# docs/deployment/operations.md.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
exec "$SCRIPT_DIR/wv" backup "$@"
