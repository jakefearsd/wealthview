#!/bin/bash
set -euo pipefail

BACKUP_DIR="backups"
TIMESTAMP=$(date +%Y-%m-%d_%H-%M)
FILENAME="wealthview_${TIMESTAMP}.dump"

mkdir -p "${BACKUP_DIR}"

echo "Backing up to ${BACKUP_DIR}/${FILENAME}..."
docker compose exec -T db pg_dump -Fc -U wv_app wealthview > "${BACKUP_DIR}/${FILENAME}"

SIZE=$(du -h "${BACKUP_DIR}/${FILENAME}" | cut -f1)
echo "Backup complete: ${BACKUP_DIR}/${FILENAME} (${SIZE})"
