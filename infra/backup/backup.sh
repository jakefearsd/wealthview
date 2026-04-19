#!/bin/sh
set -eu

BACKUP_DIR="/backups"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TIMESTAMP=$(date +%Y-%m-%d_%H-%M)
FILENAME="wealthview_${TIMESTAMP}.dump"

echo "$(date -Iseconds) Starting backup: ${FILENAME}"

pg_dump -Fc -f "${BACKUP_DIR}/${FILENAME}"

if [ $? -eq 0 ]; then
    SIZE=$(du -h "${BACKUP_DIR}/${FILENAME}" | cut -f1)
    echo "$(date -Iseconds) Backup complete: ${FILENAME} (${SIZE})"
else
    echo "$(date -Iseconds) ERROR: pg_dump failed" >&2
    exit 1
fi

DELETED=$(find "${BACKUP_DIR}" -name "wealthview_*.dump" -mtime "+${RETENTION_DAYS}" -print -delete | wc -l)
if [ "${DELETED}" -gt 0 ]; then
    echo "$(date -Iseconds) Cleaned up ${DELETED} backup(s) older than ${RETENTION_DAYS} days"
fi
