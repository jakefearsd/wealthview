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
sudo cp -r infra /etc/wealthview/
sudo cp bin/wv.conf.example /etc/wealthview/wv.conf
sudo cp .env.example /etc/wealthview/.env
sudo chmod 0600 /etc/wealthview/.env
sudo install -d -m 0755 /var/lib/wealthview/backups

# Edit /etc/wealthview/wv.conf to point at the right paths
# Edit /etc/wealthview/.env to fill in real secrets
sudo wv config-check
sudo wv up
```

The symlink in step one matters: the dispatcher locates `wv-lib` next to
itself, so `/usr/local/bin/wv-lib` has to resolve.

The dispatcher resolves its config file in this order (first match wins):
`--config FILE` → `$WV_CONFIG_FILE` → `/etc/wealthview/wv.conf` →
`$XDG_CONFIG_HOME/wealthview/wv.conf` → `~/.config/wealthview/wv.conf` →
source-tree fallback. The config file is shell-syntax `KEY=VALUE` only;
`wv` refuses to source anything else. Real secrets stay in `.env`.

Recognised `wv.conf` keys — all optional, defaults in brackets:
`WV_COMPOSE_FILE`, `WV_COMPOSE_OVERRIDE_FILE`, `WV_ENV_FILE` [`$WV_ROOT/.env`],
`WV_BACKUPS_DIR` [`$WV_ROOT/backups`], `WV_COMPOSE_PROJECT` [`wealthview`],
`WV_APP_PORT` [`80`], `WV_HEALTH_URL`, `WV_PREVIOUS_IMAGE_FILE`
[`$WV_ROOT/.wv-previous-image`], `WV_HOST`. See `bin/wv.conf.example`.

**Two things to know about the source-less layout.** Paths inside
`docker-compose.prod.yml` are relative to the compose file, not to `wv.conf`:

- The `app` service is image-only — no `build:` key, nothing to compile on the
  host. It resolves
  `${WEALTHVIEW_IMAGE:-ghcr.io/jakefearsd/wealthview}:${WEALTHVIEW_VERSION}`
  and pulls it. The `backup` sidecar does still declare
  `build: ./infra/backup`, which is why the install copies `infra/` across.
  `wv up` passes `--build`, and in prod that builds the `backup` image and
  nothing else.
- The `backup` container bind-mounts `./backups`, i.e.
  `/etc/wealthview/backups` — **not** the `WV_BACKUPS_DIR` from `wv.conf`.
  Point `WV_BACKUPS_DIR` at the same directory, or symlink one to the other,
  so `wv backups` sees the cron'd dumps too.

The GHCR package is public — it inherits the repository's visibility — so this
host pulls the image with no credentials and no registry setup. Only a private
fork or mirror needs `docker login ghcr.io` with a `read:packages` token before
`wv update` can pull. See [upgrading.md](upgrading.md#registry-access).

---

## Subcommand index

| Subcommand | What it does |
|---|---|
| `up` | Start the stack. `--no-build` `--no-detach` `--no-wait`. Passes `--build` by default; in prod that only rebuilds the `backup` sidecar, since `app` has no build section |
| `down` | Stop the stack. `--with-volumes` destroys data (prompts) |
| `restart` | `down` then `up`; extra args are forwarded to `up` |
| `status` | `compose ps` + one-shot health probe |
| `logs [service]` | Tail logs. `--tail N` `--no-follow` |
| `psql` | Interactive `psql -U wv_app wealthview` in the db container |
| `backup` | `pg_dump -Fc`. `--encrypt` `--remote` `--label TEXT` `--dry-run` |
| `backups` (`list-backups`) | List backups with size + age |
| `restore <file>` | `pg_restore --clean --if-exists`. `--dry-run` |
| `verify <file>` | Round-trip a dump through a throwaway `postgres:16` |
| `update` | Backup → pull (dev: build) → swap → health-check → auto-rollback. `--no-pull` `--build` `--no-rollback` (`--no-build` is a deprecated alias for `--no-pull`) |
| `rollback` | Revert the app to the image recorded by the last `update`; re-pins `WEALTHVIEW_IMAGE` and `WEALTHVIEW_VERSION` |
| `migrate-out` | Build a portable bundle. `--dest DIR` `--no-encrypt` |
| `migrate-in <bundle>` | Restore from a bundle on a fresh host |
| `rotate-secret <NAME>` | Regenerate a secret. `--dry-run` |
| `config-check` | Validate config, env file, compose file, tools |
| `help` (`-h`, `--help`) | Operator man page |

Global flags, consumed **before** the subcommand: `--config FILE` (or
`--config=FILE`) and `--host USER@HOST` (or `--host=USER@HOST`). Environment
equivalents: `WV_CONFIG_FILE`, `WV_HOST`. `WV_ASSUME_YES=1` auto-confirms every
interactive prompt — use it for cron and other non-interactive callers.

---

## Tools you need installed

`config-check` treats the first group as required (missing one fails the check)
and the second as optional.

| Tool | Required? | Needed for | Install |
|---|---|---|---|
| `docker` + `docker compose` plugin | required | everything | https://docs.docker.com/engine/install/ |
| `curl` | required | health probes, `status` | preinstalled |
| `python3` | required | parsing `compose images` / `compose config` JSON in `update` | preinstalled |
| `openssl` | required | secret generation in `rotate-secret` | preinstalled |
| `bash` 4+ | required | everything | preinstalled on Linux |
| `age` / `age-keygen` | optional | `--encrypt` backups, `migrate-out`, encrypted restore/verify | https://github.com/FiloSottile/age/releases (download `age-v*-linux-amd64.tar.gz`, `install -m755 age/age age/age-keygen ~/.local/bin/`) |
| `rsync` | optional | `BACKUP_REMOTE_DEST=user@host:/path` or `rsync://…` | `apt-get install rsync` |
| `aws` CLI | optional | `BACKUP_REMOTE_DEST=s3://...` | https://docs.aws.amazon.com/cli/ |
| `gitleaks` | optional | `config-check` also scans the staged diff when present | https://github.com/gitleaks/gitleaks/releases |

Run `./wv config-check` to see what is missing. It also prints the resolved
config file, env file, compose file (and override, if one is in play), backups
directory, project name, and remote host; validates that the required env vars
are present and non-placeholder; and runs `docker compose config -q` to confirm
the compose file parses. It exits non-zero if any required check fails.

---

## First-time deploy on a new host

```bash
# 1. Get the code.
git clone https://github.com/<you>/wealthview.git /opt/wealthview
cd /opt/wealthview

# 2. Create your secrets. All four are required — compose refuses to
#    interpolate a missing one, and wv refuses to start while any is CHANGE_ME.
cp .env.example .env
nano .env    # DB_PASSWORD, JWT_SECRET (32+ chars), SUPER_ADMIN_PASSWORD,
             # MFA_ENCRYPTION_KEY (openssl rand -base64 32)
chmod 600 .env

# 3. (Production only) pin the release to pull and set the CORS origin.
#    Setting WEALTHVIEW_VERSION is what flips wv into prod mode.
echo 'WEALTHVIEW_VERSION=1.2.5' >> .env
echo 'CORS_ORIGIN=https://wealthview.example.com' >> .env

# 4. Validate everything.
./wv config-check

# 5. Bring it up. In prod this pulls the published app image; in dev it builds
#    locally (first build 2-5 minutes, subsequent builds <60s).
./wv up

# 6. Verify.
./wv status
```

`./wv up` runs `docker compose up --build -d`, then waits up to 120s for
`/actuator/health` to return UP. If the wait times out, the script reports the
failure and points you at `./wv logs app`. Flags: `--no-build` skips the build
step, `--no-detach` runs in the foreground, `--no-wait` skips the health poll.

**`--build` means less in prod than it looks.** `docker-compose.prod.yml` gives
the `app` service no `build:` key, so compose builds only the `backup` sidecar
and pulls `app` from the registry. In dev, where `docker-compose.yml` still
declares `build: .`, `--build` rebuilds the app image from source as it always
did.

The health URL is `$WV_HEALTH_URL` when set, otherwise
`http://<host>:<port>/actuator/health`, where the port comes from `APP_PORT` in
the env file (falling back to `WV_APP_PORT`, default 80) and the host is
`localhost` unless `WV_HOST` is set.

---

## Update an existing deployment

```bash
nano .env            # WEALTHVIEW_VERSION=1.2.5
./wv update
```

The image is **pulled**, not built: CI publishes
`ghcr.io/<owner>/wealthview:<version>` on every `v*` tag once the unit tests,
quality gates and full integration suite pass. There is nothing to `git pull`
on the host, and no JDK is required there.

`./wv update` first validates `.env` and confirms the `db` container is
running — it aborts with "run './wv up' first" otherwise, because it cannot
take its pre-update backup against a stopped database. Then, in order:

1. **Step 1/5** — takes a labelled pre-update backup
   (`wealthview_<ts>_pre-update.dump` in the backups directory).
2. **Step 2/5** — records the currently-running app image reference (repository
   **and** tag) to `.wv-previous-image` so `./wv rollback` has a target. If it
   can't determine the image it warns and continues; rollback will then be
   unavailable.
3. **Step 3/5** — `docker compose pull app` in prod mode. In dev mode it builds
   instead: the dev compose file builds from source and has no image to pull.
4. **Step 4/5** — `docker compose up -d --no-deps app`. Only the app container
   is recreated; the db keeps running and its volume is untouched.
5. **Step 5/5** — waits up to 180s for the health endpoint.

If step 5 fails, `update` automatically rolls the app container back to the
previously recorded image (waiting a further 120s for that to come healthy).
Pass `--no-rollback` to leave the failing container in place for inspection.

Flags on step 3:

| Flag | Effect |
|---|---|
| `--no-pull` | Skip the fetch; reuse the local image for the resolved tag. |
| `--no-build` | Deprecated alias for `--no-pull`. Still works, prints a warning. |
| `--build` | Build locally instead of pulling. **Rejected on `docker-compose.prod.yml`**, whose `app` service has no `build:` key — `docker compose build` on such a service exits 0 having done nothing, which would deploy a stale image while reporting success, so `wv update` checks first and fails loudly. |

Exit codes:
- **0** — update healthy
- **1** — update failed; rollback succeeded
- **2** — update failed; rollback also failed (manual intervention required)

If the pull fails, the error names the two usual causes: `WEALTHVIEW_VERSION`
does not name a published release, or this host cannot read the registry (the
upstream package is public, so that means a private fork or mirror, which needs
`docker login ghcr.io` with a `read:packages` token). Nothing has been swapped
at that point.

---

## Manual rollback

`./wv rollback` reverts the app to the image recorded in `.wv-previous-image`
by the last successful `./wv update` (path overridable with
`WV_PREVIOUS_IMAGE_FILE`). It fails with a clear error if no previous image was
ever recorded. In prod mode it splits the recorded reference and re-ups the app
with **both** `WEALTHVIEW_IMAGE` and `WEALTHVIEW_VERSION` set, then waits up to
120s for health. Restoring both halves matters: the reference is now
registry-qualified, so re-pinning only the tag would move a mirrored or forked
deployment back onto the default upstream repository. A reference with no
parseable tag stops the rollback with instructions rather than a guess — and a
registry `host:port` is correctly read as untagged, not as a tag named `5000`.

The database is **not** rewound; if the new version applied schema changes you
cannot live with, restore the matching pre-update backup first:

```bash
./wv backups                                        # find the right pre-update file
./wv restore backups/wealthview_<ts>_pre-update.dump
./wv rollback
```

Afterwards, set `WEALTHVIEW_VERSION` in `.env` back to the old tag so a later
`./wv up` doesn't silently move you forward again.

---

## On-demand backup

```bash
./wv backup                                # plaintext, local only
./wv backup --encrypt                      # age-encrypted
./wv backup --encrypt --remote             # encrypted + uploaded
./wv backup --label pre-experiment         # custom suffix
./wv backup --dry-run                      # show what would happen
```

Backups land in the resolved backups directory (`./backups/` from a checkout,
`$WV_BACKUPS_DIR` otherwise), named `wealthview_<UTC-timestamp>[_label].dump`
— or `.dump.age` when encrypted, in which case the plaintext dump is deleted
so it never lingers on disk. Labels accept letters, digits, dash, and
underscore only. `backup` prints the final path on stdout as its last line, so
it composes in scripts.

In prod mode, an unencrypted backup logs a warning: financial data is now
sitting in plaintext on disk.

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
isn't paged when the network blips. `--remote` with `BACKUP_REMOTE_DEST`
unset warns and skips rather than failing.

---

## Backup verification

A backup that can't be restored is worse than no backup. Verify periodically:

```bash
./wv backups                            # see what you have
./wv verify backups/wealthview_<ts>.dump
```

`verify` spins up a throwaway `postgres:16` container with a unique random
password, restores the backup into it, runs sanity queries (top five tables by
row count, then the total `public` table count), then tears the container and
its volume down. It fails if no user tables are present or if fewer than five
public tables exist — a strong signal the dump was truncated. `.dump.age`
files are decrypted on the fly via `BACKUP_ENCRYPTION_KEY_FILE`. The live
database is never touched. The whole cycle takes well under a minute on a
small backup.

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
./wv migrate-out                       # -> <backups>/migrations/wealthview-bundle-<ts>.tar.gz
./wv migrate-out --dest /mnt/usb       # write the bundle somewhere else
./wv migrate-out --no-encrypt          # NOT RECOMMENDED — the bundle holds your whole DB
```

`migrate-out` refuses to run without `BACKUP_ENCRYPTION_RECIPIENT` unless you
pass `--no-encrypt`.

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

# 2. Set up .env: the four required secrets (DB_PASSWORD, JWT_SECRET,
#    SUPER_ADMIN_PASSWORD, MFA_ENCRYPTION_KEY), CORS_ORIGIN, the
#    WEALTHVIEW_VERSION from VERSION.pin, and BACKUP_ENCRYPTION_KEY_FILE
#    pointing at the age identity that owns the bundle.
#
#    Carry JWT_SECRET and MFA_ENCRYPTION_KEY over from the old host — a new
#    JWT_SECRET only logs everyone out, but a new MFA_ENCRYPTION_KEY makes
#    every restored TOTP secret undecryptable.
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
./wv rotate-secret SUPER_ADMIN_PASSWORD # updates the DB row in place
./wv rotate-secret DB_PASSWORD          # ALTER USER + .env
```

Exactly these three names are accepted; anything else exits with
"Unknown secret name". **`MFA_ENCRYPTION_KEY` is deliberately not rotatable
here** — it decrypts the TOTP secrets stored at rest, so swapping it would lock
every MFA-enrolled user out. Changing it is a manual operation that needs a
plan for re-enrolling those users first.

Each rotation:

1. Generates a fresh value with `openssl rand -base64` — 48 bytes for
   `JWT_SECRET`, 24 for the other two.
2. Rewrites the variable in `.env` in place, preserving every other line and
   the file's permissions.
3. Applies the change to the running stack in the right order:
   - `JWT_SECRET` — restarts the app. Every existing JWT becomes invalid and
     users must log in again.
   - `SUPER_ADMIN_PASSWORD` — updates the `users` row for
     `admin@wealthview.local` directly when python3's `bcrypt` module is
     available; otherwise it warns and restarts the app so
     `SuperAdminInitializer` syncs the new value at boot.
   - `DB_PASSWORD` — runs `ALTER USER wv_app WITH PASSWORD …` against the live
     database first, then restarts the app. (The `pgdata` volume is initialised
     once with the original password; this is what keeps `.env` and the role in
     sync.)
4. Waits for the health check and prompts you to take a fresh backup so
   the new state is captured.

`--dry-run` reports what would change without writing anything.

---

## Day-to-day operational commands

```bash
./wv status                    # compose ps + one-shot health probe
./wv logs                      # tail all services
./wv logs app                  # tail a single service (db, app, backup)
./wv logs --tail 100 app       # last 100 lines, then follow
./wv logs --no-follow app      # print and exit — the form to use in scripts
./wv psql                      # interactive psql shell as wv_app
./wv restart                   # down then up (volumes preserved)
./wv restart --no-build        # extra args after `restart` go to `up`
./wv down                      # stop, preserve data volumes
./wv down --with-volumes       # DESTROY all data (prompts for confirmation)
```

### Driving a remote host

Set `WV_HOST` in `wv.conf` or pass `--host user@host` and every `docker
compose` call runs with `DOCKER_HOST=ssh://$WV_HOST`:

```bash
wv --host root@wealthview.example.com status
wv --host root@wealthview.example.com update
```

You need ssh-agent with an authorised key, the host in `known_hosts`, and the
docker CLI locally — the local daemon does not need to be running. Paths inside
`wv.conf` refer to the **remote** filesystem. One asymmetry to plan around:
`wv backup` writes to `$WV_BACKUPS_DIR` on the machine you ran it from, because
`pg_dump` streams back over the `docker compose exec` pipe. To land a backup on
the remote host itself, run `wv` from a shell on that host.

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
| `./wv update` aborts: "db container is not running" | `update` needs a live database to take its pre-update backup | `./wv up` first, then re-run `./wv update` |
| `./wv rollback` errors "No previous image recorded" | `update` never ran, or it could not read the running image tag at Step 2/5 | Pin a known-good `WEALTHVIEW_VERSION` in `.env` and run `./wv up` |
| `./wv update` stops at Step 3/5: "Image pull failed" | `WEALTHVIEW_VERSION` names a tag that was never published; or this host pulls from a private fork/mirror with no credential (the upstream package is public) | Check the tag exists on the repo's Releases/Packages page; for a private package, `docker login ghcr.io` with a `read:packages` PAT |
| `./wv update --build` refuses: "has no 'build:' key" | `docker-compose.prod.yml` is image-only by design | Drop `--build` and pull; use `--no-pull` if the image is already loaded locally |
| `docker compose pull` says "no matching manifest for linux/arm64" | The published image is `linux/amd64` only | Deploy on x86-64, or build your own image on the ARM host and use `wv update --no-pull` |
| `wv` exits: "No wv.conf found ... and \<dir\> is not a source tree" | Running the system-wide `wv` with no config file | Create `/etc/wealthview/wv.conf` or pass `--config /path/to/wv.conf` |
| `wv` exits: "Refusing to source ...: unexpected line" | `wv.conf` contains something other than `KEY=VALUE` / comments | Strip the logic; `wv.conf` is declarative only |
| `./wv up` fails building the `backup` image on a source-less host | `build: ./infra/backup` has no `infra/` directory beside the compose file | Copy `infra/` next to the compose file (see the install above), or `./wv up --no-build` |
| `./wv status` says health endpoint not reachable, but the app is fine | Probe targets `http://localhost:$APP_PORT`, which is wrong behind a proxy or on a remote host | Set `WV_APP_PORT` or `WV_HEALTH_URL` in `wv.conf` |

---

## Where the cron'd backup container fits

The production compose file (`docker-compose.prod.yml`) starts a separate
`backup` container — Alpine 3.20 with `postgresql16-client`, running `crond`
with a single entry, `0 3 * * *`. That is 03:00 in the container's timezone,
which is UTC unless you change it. Each run writes
`wealthview_auto_<YYYY-MM-DD_HH-MM>.dump` and then deletes only its own dumps
older than `BACKUP_RETENTION_DAYS` (default 14) — the `auto_` marker is what
retention keys off, so an on-demand `./wv backup` can never be swept. It lives
entirely inside the compose stack and continues to work unchanged.

`./wv backup` is for **on-demand** backups (pre-experiment, pre-upgrade,
ad-hoc). The two coexist and share a directory — but only if the paths line
up. The container bind-mounts `./backups` relative to the compose file, while
`wv` writes to `$WV_BACKUPS_DIR`. From a source checkout those are the same
`./backups/`. On a source-less install, set `WV_BACKUPS_DIR` to match (see the
caveats under **Installing on a production server**), or `wv backups` will not
list the cron'd dumps.

You can trigger the container's script manually:

```bash
docker compose -f docker-compose.prod.yml exec backup /backup.sh
```

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
- `deploy.sh` still handles its specialised build-here-ship-there flow: build
  the image on a workstation, `docker save`, `scp`, `docker load`, then
  `docker compose up -d` over SSH. It predates the registry flow and is no
  longer the recommended path — `./wv update` assumes shell access on the
  deployment host and adds the pre-update backup, health check, and
  auto-rollback that `deploy.sh` has none of. Two things to watch: it copies
  `docker-compose.prod.yml` to the remote **as `docker-compose.yml`** (point
  `WV_COMPOSE_FILE` at that path afterwards), and it builds and loads the image
  as `wealthview:<version>`, so the remote `.env` must also set
  `WEALTHVIEW_IMAGE=wealthview` or compose will look for the GHCR reference
  instead.
- `infra/backup/restore.sh` is the backup container's own restore helper. It
  hardcodes `docker compose -f docker-compose.prod.yml` and does no
  decryption; prefer `./wv restore`, which also stops the app, terminates
  lingering connections, and health-checks afterwards.

If you script against the old names, nothing breaks — but new automation
should call `./wv` directly.
