# Ops files — production deployment

    docker-compose.prod.yml   the stack, with the storage bind mount
    .env.prod.example         secrets template → copy to .env, chmod 600
    backup-documents.sh       nightly documents + database backup

## Before the first start

    sudo mkdir -p /var/lib/press-accreditation/storage
    sudo mkdir -p /etc/press-accreditation/keys
    sudo chown -R 1000:1000 /var/lib/press-accreditation/storage   # container UID
    sudo chmod 750 /var/lib/press-accreditation/storage

    # confirm it is a REAL filesystem, not tmpfs/overlay
    df -hT /var/lib/press-accreditation/storage

    cp .env.prod.example .env && chmod 600 .env    # then fill in the secrets
    docker compose -f docker-compose.prod.yml up -d

The application verifies the storage directory at startup and REFUSES TO BOOT
if it cannot write there. Check the log for:

    Document storage: /var/lib/press-accreditation/storage (ext4, 3% used, 91.2 GB free)

## Install the backup

    sudo cp backup-documents.sh /usr/local/bin/
    sudo chmod +x /usr/local/bin/backup-documents.sh
    sudo crontab -e
      15 2 * * *  BACKUP_DIR=/mnt/backup/press-accreditation POSTGRES_CONTAINER=press-postgres /usr/local/bin/backup-documents.sh >> /var/log/press-backup.log 2>&1

Set POSTGRES_CONTAINER when Postgres runs in Docker (as above); omit it for a
host installation.

## Two things worth doing once, before go-live

1. Run the backup by hand and read the log.
2. RESTORE DRILL — extract the archive to a scratch directory, restore the
   dump into a scratch database, open a document, confirm its row exists.
   An untested backup is a hope, not a plan.

## Network shape
  press-internal : postgres + backend   (the frontend cannot reach the DB)
  press-edge     : backend + frontend   (fronted by the reverse proxy)
Both app ports bind to 127.0.0.1, so only the reverse proxy is exposed.
