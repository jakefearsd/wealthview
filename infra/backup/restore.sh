#!/bin/bash
set -euo pipefail

if [ $# -eq 0 ]; then
    echo "Usage: $0 <backup-file>"
    echo ""
    echo "Available backups:"
    ls -lh backups/wealthview_*.dump 2>/dev/null || echo "  No backups found in backups/"
    exit 1
fi

BACKUP_FILE="$1"

if [ ! -f "${BACKUP_FILE}" ]; then
    echo "ERROR: File not found: ${BACKUP_FILE}"
    exit 1
fi

echo "This will restore from: ${BACKUP_FILE}"
echo "WARNING: This replaces all data in the wealthview database."
read -rp "Continue? (y/N) " confirm
if [ "${confirm}" != "y" ] && [ "${confirm}" != "Y" ]; then
    echo "Aborted."
    exit 0
fi

echo "Stopping app to avoid active connections..."
docker compose -f docker-compose.prod.yml stop app

echo "Restoring database..."
docker compose -f docker-compose.prod.yml exec -T db \
    pg_restore --clean --if-exists -U wv_app -d wealthview < "${BACKUP_FILE}"

echo "Starting app..."
docker compose -f docker-compose.prod.yml start app

echo "Restore complete."
