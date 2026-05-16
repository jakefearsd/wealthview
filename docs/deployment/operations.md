[<- Back to README](../../README.md)

# WealthView Admin Operations Handbook

Every routine operation goes through one entry point: `wv`. The script
lives at `bin/wv` (with subcommand libraries in `bin/wv-lib/`); the
`./wv` at the repo root is a convenience shim for development. This is
the admin's command surface for both dev and prod stacks.

```
wv help          # full operator man page
wv <sub> -h      # per-subcommand details
```

`wv` runs in one of two configurations:

- **Source tree (dev):** invoked from a checkout. It reads
  `docker-compose.yml` / `docker-compose.prod.yml` and `.env` from the
  repo. Mode auto-detects: if `WEALTHVIEW_VERSION` is set in `.env`, the
  prod compose file is used; otherwise the dev compose file.
- **Production install:** installed system-wide (e.g.
  `/usr/local/bin/wv`) on a server that has only containers. All paths
  come from a config file at `/etc/wealthview/wv.conf`. The source tree
  is not required on the host. See **Installing on a production server**
  below.

`wv` can also drive a remote Docker daemon over SSH — set `WV_HOST` in
the config file (or pass `--host user@host`) and every `docker compose`
call runs against `DOCKER_HOST=ssh://$WV_HOST`. Requires ssh-agent with
an authorised key on the remote.

This handbook walks each routine operation end-to-end.

---

## Installing on a production server

The minimum a server needs:

```
/usr/local/bin/wv                  # dispatcher
/usr/local/lib/wv-lib/             # subcommand implementations
/etc/wealthview/wv.conf            # paths + project name + remote host
/etc/wealthview/.env               # secrets (DB_PASSWORD, JWT_SECRET, ...)
/etc/wealthview/docker-compose.prod.yml
/var/lib/wealthview/backups/       # backup output directory
```

One-time install from a checkout:

```bash
sudo install -m 0755 bin/wv /usr/local/bin/wv
sudo install -d -m 0755 /usr/local/lib/wv-lib
sudo install -m 0644 bin/wv-lib/*.sh /usr/local/lib/wv-lib/
sudo ln -snf /usr/local/lib/wv-lib /usr/local/bin/wv-lib

sudo install -d -m 0755 /etc/wealthview
sudo cp docker-compose.prod.yml /etc/wealthview/
sudo cp bin/wv.conf.example /etc/wealthview/wv.conf
sudo cp .env.example /etc/wealthview/.env
sudo chmod 0600 /etc/wealthview/.env
sudo install -d -m 0755 /var/lib/wealthview/backups

# Edit /etc/wealthview/wv.conf to point at the right paths
# Edit /etc/wealthview/.env to fill in real secrets
sudo wv config-check
sudo wv up
```

The dispatcher resolves its config file in this order (first match wins):
`--config FILE` → `$WV_CONFIG_FILE` → `/etc/wealthview/wv.conf` →
`$XDG_CONFIG_HOME/wealthview/wv.conf` → `~/.config/wealthview/wv.conf` →
source-tree fallback. The config file is shell-syntax `KEY=VALUE` only;
`wv` refuses to source anything else. Real secrets stay in `.env`.

---

## Tools you need installed

| Tool | Required for | Install |
|---|---|---|
| `docker` + `docker compose` plugin | everything | https://docs.docker.com/engine/install/ |
| `bash` 4+ | everything | preinstalled on Linux |
| `curl` | health probes, status | preinstalled |
| `python3` | small parsers used internally | preinstalled |
| `openssl` | secret generation in `rotate-secret` | preinstalled |
| `age` | `--encrypt` backups, `migrate-out`, encrypted restore | https://github.com/FiloSottile/age/releases (download `age-v*-linux-amd64.tar.gz`, `install -m755 age/age age/age-keygen ~/.local/bin/`) |
| `rsync` (optional) | `BACKUP_REMOTE_DEST=user@host:/path` | `apt-get install rsync` |
| `aws` CLI (optional) | `BACKUP_REMOTE_DEST=s3://...` | https://docs.aws.amazon.com/cli/ |

Run `./wv config-check` to see what is missing.

---

## First-time deploy on a new host

```bash
# 1. Get the code.
git clone https://github.com/<you>/wealthview.git /opt/wealthview
cd /opt/wealthview

# 2. Create your secrets.
cp .env.example .env
nano .env    # fill DB_PASSWORD, JWT_SECRET, SUPER_ADMIN_PASSWORD
chmod 600 .env

# 3. (Production only) pin a version.
echo 'WEALTHVIEW_VERSION=1.0.0' >> .env

# 4. Validate everything.
./wv config-check

# 5. Bring it up. The first build is 2-5 minutes; subsequent builds are <60s.
./wv up

# 6. Verify.
./wv status
```

`./wv up` waits for `/actuator/health` to return UP before returning
success. If the wait times out, the script reports the failure and points
you at `./wv logs app`.

---

## Update an existing deployment

```bash
git pull origin main
./wv update
```

`./wv update` does five things in order:

1. Validates `.env`.
2. Takes a labelled pre-update backup
   (`backups/wealthview_<ts>_pre-update.dump`).
3. Records the currently-running app image so `./wv rollback` has a target.
4. Builds the new image (`--no-build` to skip).
5. Recreates the `app` container and waits up to 180s for the health endpoint.

If step 5 fails, `update` automatically rolls the app container back to the
previously recorded image. Pass `--no-rollback` to leave the failing
container in place for inspection.

Exit codes:
- **0** — update healthy
- **1** — update failed; rollback succeeded
- **2** — update failed; rollback also failed (manual intervention required)

---

## Manual rollback

`./wv rollback` reverts the app to the image saved by the last successful
`./wv update`. The database is **not** rewound; if the new version applied
schema changes you cannot live with, restore the matching pre-update
backup first:

```bash
./wv backups                                        # find the right pre-update file
./wv restore backups/wealthview_<ts>_pre-update.dump
./wv rollback
```

---

## On-demand backup

```bash
./wv backup                                # plaintext, local only
./wv backup --encrypt                      # age-encrypted
./wv backup --encrypt --remote             # encrypted + uploaded
./wv backup --label pre-experiment         # custom suffix
./wv backup --dry-run                      # show what would happen
```

Backups land in `./backups/` named `wealthview_<UTC-timestamp>[_label].dump`
(or `.dump.age` when encrypted).

### Encryption (recommended for anything leaving the host)

Generate an age identity once and store it somewhere the host can read but
the world cannot:

```bash
age-keygen -o /etc/wealthview/backup.key
chmod 600 /etc/wealthview/backup.key
grep '# public key:' /etc/wealthview/backup.key   # copy the age1... value
```

Wire it into `.env`:

```dotenv
BACKUP_ENCRYPTION_RECIPIENT=age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
BACKUP_ENCRYPTION_KEY_FILE=/etc/wealthview/backup.key
```

`--encrypt` then refuses to run if the recipient is missing, and `restore`
/ `verify` decrypt `.age` files automatically using the key file.

### Off-host copy

Set `BACKUP_REMOTE_DEST` in `.env` to one of:

- `s3://bucket/path/` — needs the `aws` CLI installed and configured
- `user@host:/path/` — SSH/rsync
- `rsync://host/module/path/` — rsync daemon

Then:

```bash
./wv backup --encrypt --remote
```

The off-host copy is best-effort: if the upload fails, the local backup
still succeeded. The script logs the failure and exits 0 so a backup cron
isn't paged when the network blips.

---

## Backup verification

A backup that can't be restored is worse than no backup. Verify periodically:

```bash
./wv backups                            # see what you have
./wv verify backups/wealthview_<ts>.dump
```

`verify` spins up a throwaway PostgreSQL container with a unique random
password, restores the backup into it, runs sanity queries (top tables by
row count, total table count), then tears the container down. The whole
cycle takes well under a minute on a small backup.

This is safe to schedule weekly via `cron`.

---

## Restore from a backup

```bash
./wv restore backups/wealthview_<ts>.dump
```

The script prompts for confirmation, stops the app, kills lingering DB
connections, runs `pg_restore --clean --if-exists`, restarts the app, and
waits for the health check.

`.dump.age` files are decrypted automatically using `BACKUP_ENCRYPTION_KEY_FILE`.

For automation: `WV_ASSUME_YES=1 ./wv restore <file>` skips the prompt.

---

## Host migration (move the deployment to another machine)

### On the source host

```bash
./wv migrate-out
# -> backups/migrations/wealthview-bundle-<ts>.tar.gz
```

The bundle contains:

- An age-encrypted database backup (`*.dump.age`)
- `.env.example` skeleton
- The `infra/` directory (backup container source)
- `VERSION.pin` recording the WEALTHVIEW_VERSION currently in use
- A `README.txt` with restore steps

Move the bundle to the new host (`scp`, USB, whatever).

### On the destination host

```bash
# 1. Clone the repo at the version pinned in the bundle's VERSION.pin.
git clone https://github.com/<you>/wealthview.git /opt/wealthview
cd /opt/wealthview

# 2. Set up .env, including BACKUP_ENCRYPTION_KEY_FILE pointing at the
#    age identity that owns the bundle.
cp .env.example .env
nano .env
chmod 600 .env

# 3. Bring the stack up so the database container exists.
./wv up

# 4. Pull the data in.
./wv migrate-in /path/to/wealthview-bundle-<ts>.tar.gz
```

`migrate-in` extracts the bundle, locates the `.dump`/`.dump.age`,
decrypts it if needed, and runs `./wv restore` automatically. The app
restarts and is health-checked at the end.

---

## Rotate a secret

```bash
./wv rotate-secret JWT_SECRET           # invalidates all sessions
./wv rotate-secret SUPER_ADMIN_PASSWORD # updates DB row in place
./wv rotate-secret DB_PASSWORD          # updates DB role + .env
```

Each rotation:

1. Generates a fresh value with `openssl rand -base64`.
2. Updates `.env` in place (preserves other lines, mode-safe).
3. Applies the change to the running stack in the right order
   (e.g., `ALTER USER` for `DB_PASSWORD` before restarting the app).
4. Waits for the health check and prompts you to take a fresh backup so
   the new state is persisted.

`--dry-run` prints what would change without writing anything.

---

## Day-to-day operational commands

```bash
./wv status                 # ps + one-shot health probe
./wv logs                   # tail all services
./wv logs app               # tail a single service
./wv logs --tail 100 app    # last 100 lines, then follow
./wv psql                   # interactive psql shell as wv_app
./wv down                   # stop, preserve data volumes
./wv down --with-volumes    # DESTROY all data (prompts for confirmation)
```

---

## Common failure modes and their fixes

| Symptom | Likely cause | Fix |
|---|---|---|
| `./wv config-check` reports `Placeholder value still in .env` | `.env` still has `CHANGE_ME` | Edit `.env` and replace placeholders |
| `./wv up` hangs at health check | App container is failing to start | `./wv logs app` to see the stack trace |
| `./wv backup --encrypt` errors `requires BACKUP_ENCRYPTION_RECIPIENT` | No age public key in `.env` | Generate one with `age-keygen` and set `BACKUP_ENCRYPTION_RECIPIENT` |
| `./wv restore` fails decrypting `.age` | `BACKUP_ENCRYPTION_KEY_FILE` is unset or wrong | Point it at the age identity that pairs with the recipient used at backup time |
| `./wv update` rolled back automatically | New image fails health check | `./wv logs app` to find the cause; fix and re-run; or `./wv rollback` if you need to fully revert |
| `./wv verify` reports "only N public tables; backup may be truncated" | Dump file truncated mid-write | Take a fresh backup; investigate disk space at backup time |
| `./wv rotate-secret DB_PASSWORD` succeeded but app won't start | `.env` and DB role got out of sync (rare; only if `ALTER USER` failed silently) | Run `./wv psql` and `ALTER USER wv_app WITH PASSWORD '<value-from-env>'` |

---

## Where the cron'd backup container fits

The production compose file (`docker-compose.prod.yml`) starts a separate
`backup` container that runs `pg_dump` on a cron schedule (default 03:00
UTC daily). That continues to work unchanged — it lives entirely inside
the compose stack.

`./wv backup` is for **on-demand** backups (pre-experiment, pre-upgrade,
ad-hoc). The two coexist; their output files share the `./backups/`
directory.

If you want to encrypt the cron'd backups too, that lives in
`infra/backup/` and is intentionally out of scope for this script —
encryption keys for an unattended container are a different threat-model
discussion.

---

## What the existing dev-* scripts do now

- `dev-backup.sh` is now a thin shim that calls `./wv backup`.
- `dev-restore.sh` is now a thin shim that calls `./wv restore`
  (or `./wv backups` when invoked with no arguments, preserving the old
  "show me what I have" ergonomic).
- `deploy.sh` still handles its specialised SSH-from-workstation flow;
  this is a different shape of operation than `./wv update`, which assumes
  you have shell access on the deployment host.

If you script against the old names, nothing breaks — but new automation
should call `./wv` directly.
