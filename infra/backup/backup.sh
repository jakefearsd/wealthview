#!/bin/sh
set -eu

BACKUP_DIR="/backups"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TIMESTAMP=$(date +%Y-%m-%d_%H-%M)
# Scheduled (cron) dumps carry an "auto_" marker so retention can tell them apart
# from operator-initiated `wv backup` dumps, which are named
# wealthview_<ISO-8601-UTC>[_<label>].dump and must never be auto-deleted.
# The wealthview_ prefix is kept so existing listing globs (bin/wv-lib/backups.sh,
# infra/backup/restore.sh) still find these files unchanged.
FILENAME="wealthview_auto_${TIMESTAMP}.dump"

echo "$(date -Iseconds) Starting backup: ${FILENAME}"

pg_dump -Fc -f "${BACKUP_DIR}/${FILENAME}"

if [ $? -eq 0 ]; then
    SIZE=$(du -h "${BACKUP_DIR}/${FILENAME}" | cut -f1)
    echo "$(date -Iseconds) Backup complete: ${FILENAME} (${SIZE})"
else
    echo "$(date -Iseconds) ERROR: pg_dump failed" >&2
    exit 1
fi

# Retention sweep — deletes ONLY what this container created.
#
# Approach: match the container's own dumps by name, never the bare
# wealthview_*.dump glob. That glob also matched operator backups taken with
# `wv backup` (wealthview_<ts>[_label].dump), so pre-change safety dumps were
# being aged out from under the operator.
#
# Patterns swept, plus their .age (age-encrypted) counterparts:
#   1. wealthview_auto_*.dump[.age]        — current scheduled naming
#   2. wealthview_YYYY-MM-DD_HH-MM.dump[.age] — legacy scheduled naming, still
#      on disk from before the rename. Cannot collide with `wv backup` output,
#      whose timestamps are ISO-8601 and always contain 'T' and 'Z'.
#
# Backups taken by `wv backup` (including --encrypt) are intentionally NOT
# pruned here; the operator owns their lifecycle.
LEGACY_TS='[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]_[0-9][0-9]-[0-9][0-9]'
DELETED=$(find "${BACKUP_DIR}" -maxdepth 1 -type f \
    \( -name "wealthview_auto_*.dump" \
    -o -name "wealthview_auto_*.dump.age" \
    -o -name "wealthview_${LEGACY_TS}.dump" \
    -o -name "wealthview_${LEGACY_TS}.dump.age" \) \
    -mtime "+${RETENTION_DAYS}" -print -delete | wc -l)
if [ "${DELETED}" -gt 0 ]; then
    echo "$(date -Iseconds) Cleaned up ${DELETED} backup(s) older than ${RETENTION_DAYS} days"
fi
