#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# backup-documents.sh — nightly backup of the HAPA press-accreditation system
#
# A mounted volume protects against RESTARTS. It does not protect against
# disk failure, accidental deletion, or ransomware. Uploaded documents are
# the evidence behind accreditation decisions and cannot be re-created, so
# this backup is not optional.
#
# The script archives the DOCUMENTS and dumps the DATABASE in the same run:
# they must be restorable to the same instant, or a restored file belongs to
# no application.
#
# Install (as root):
#     cp backup-documents.sh /usr/local/bin/
#     chmod +x /usr/local/bin/backup-documents.sh
#     crontab -e
#       15 2 * * *  /usr/local/bin/backup-documents.sh >> /var/log/press-backup.log 2>&1
# ═══════════════════════════════════════════════════════════════════
set -euo pipefail

# ── configuration (override via environment) ───────────────────────
STORAGE_DIR="${STORAGE_DIR:-/var/lib/press-accreditation/storage}"
BACKUP_DIR="${BACKUP_DIR:-/mnt/backup/press-accreditation}"   # a DIFFERENT disk
DB_NAME="${DB_NAME:-press_accreditation}"
DB_USER="${DB_USER:-press}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
# Set if Postgres runs in Docker; leave empty for a host installation.
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-}"

STAMP="$(date +%Y-%m-%d_%H%M)"
DEST="${BACKUP_DIR}/${STAMP}"

log()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
fail() { log "ERROR: $*"; exit 1; }

# ── preflight ──────────────────────────────────────────────────────
[[ -d "$STORAGE_DIR" ]] || fail "Storage directory not found: $STORAGE_DIR"
mkdir -p "$DEST"        || fail "Cannot create backup destination: $DEST"

# A backup on the same filesystem dies with that filesystem.
SRC_FS="$(df -P "$STORAGE_DIR" | awk 'NR==2 {print $1}')"
DST_FS="$(df -P "$BACKUP_DIR"  | awk 'NR==2 {print $1}')"
if [[ "$SRC_FS" == "$DST_FS" ]]; then
    log "WARNING: backup destination is on the SAME filesystem ($SRC_FS)."
    log "         This guards against deletion but NOT against disk failure."
    log "         Point BACKUP_DIR at another disk or machine."
fi

log "════ Backup started → ${DEST} ════"

# ── 1. documents ───────────────────────────────────────────────────
log "Archiving documents from ${STORAGE_DIR}"
tar -czf "${DEST}/documents.tar.gz" \
    -C "$(dirname "$STORAGE_DIR")" "$(basename "$STORAGE_DIR")" \
    || fail "Document archive failed"

DOC_COUNT="$(find "$STORAGE_DIR" -type f ! -name '.write-probe' | wc -l)"
DOC_SIZE="$(du -h "${DEST}/documents.tar.gz" | cut -f1)"
log "Documents archived: ${DOC_COUNT} file(s), ${DOC_SIZE} compressed"

# ── 2. database, same point in time ────────────────────────────────
log "Dumping database ${DB_NAME}"
if [[ -n "$POSTGRES_CONTAINER" ]]; then
    docker exec "$POSTGRES_CONTAINER" \
        pg_dump -U "$DB_USER" -d "$DB_NAME" -F c \
        > "${DEST}/database.dump" || fail "Database dump failed (container)"
else
    pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
        -F c -f "${DEST}/database.dump" || fail "Database dump failed"
fi
DB_SIZE="$(du -h "${DEST}/database.dump" | cut -f1)"
log "Database dumped: ${DB_SIZE}"

# ── 3. manifest + checksums ────────────────────────────────────────
# So a restore can be VERIFIED rather than assumed.
( cd "$DEST" && sha256sum documents.tar.gz database.dump > checksums.sha256 )

cat > "${DEST}/manifest.txt" <<MANIFEST
HAPA — Système d'accréditation presse
Sauvegarde du $(date '+%d/%m/%Y à %H:%M')

Hôte           : $(hostname)
Stockage       : ${STORAGE_DIR}
Base de données: ${DB_NAME}
Documents      : ${DOC_COUNT} fichier(s), ${DOC_SIZE}
Dump SQL       : ${DB_SIZE}

── VÉRIFIER avant restauration ──
    sha256sum -c checksums.sha256

── RESTAURER ──
  1. Documents :
       tar -xzf documents.tar.gz -C $(dirname "$STORAGE_DIR")
  2. Base de données :
       pg_restore -U ${DB_USER} -d ${DB_NAME} --clean --if-exists database.dump
  3. Vérifier qu'un document s'ouvre ET que sa ligne existe en base.
MANIFEST
log "Manifest and checksums written"

# ── 4. retention ───────────────────────────────────────────────────
log "Removing backups older than ${RETENTION_DAYS} days"
find "$BACKUP_DIR" -maxdepth 1 -type d -name '20*' -mtime "+${RETENTION_DAYS}" \
     -exec rm -rf {} + 2>/dev/null || true
REMAINING="$(find "$BACKUP_DIR" -maxdepth 1 -type d -name '20*' | wc -l)"

# ── 5. free-space check on the backup volume ───────────────────────
FREE_PCT="$(df -P "$BACKUP_DIR" | awk 'NR==2 {gsub("%",""); print 100-$5}')"
if (( FREE_PCT < 15 )); then
    log "WARNING: only ${FREE_PCT}% free on the backup volume — prune or extend."
fi

log "════ Backup complete. ${REMAINING} backup(s) retained, ${FREE_PCT}% free ════"
