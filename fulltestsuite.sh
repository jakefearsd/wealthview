#!/usr/bin/env bash
# Run the full test suite: backend Maven verify (unit + integration tests)
# followed by the frontend Vitest suite. Exits non-zero on the first failure.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
cd "$SCRIPT_DIR"

(cd backend && mvn verify)
(cd frontend && npm run test)
