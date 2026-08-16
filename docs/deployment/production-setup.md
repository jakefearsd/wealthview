[<- Back to README](../../README.md)

# Production Setup

This is the end-to-end guide for putting WealthView on a real machine that you
intend to use every day. It assumes only basic Docker knowledge (you have run
`docker compose up` once before) and walks through every step.

At the end you will have:

- WealthView running in Docker on a Linux host, pinned to an explicit version.
- A PostgreSQL database with nightly automatic backups.
- The `wv` admin tool installed so day-2 operations are one command each.
- An edge proxy providing TLS (HTTPS) — you pick one of three options.

For a 5-minute local trial, see [quickstart.md](quickstart.md) instead. For the
full day-2 command reference, see [operations.md](operations.md).

---

## What you are about to deploy

WealthView ships with three Compose files at the repo root:

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Local evaluation. Two services (`db`, `app`) with the `docker` Spring profile, which seeds a demo tenant. DB published on host 5433, app on host 80. Not for production. |
| `docker-compose.prod.yml` | Production. Three services: `db`, `app`, `backup`. Runs the `prod` Spring profile with strict config validation and no seed data. `restart: unless-stopped` on all three. |
| `docker-compose.observability.yml` | Optional Prometheus + Grafana overlay, layered on top of the prod file. See [Optional: observability stack](#optional-observability-stack). |

The production compose file contains **only the WealthView containers**. It
deliberately does not include nginx, certbot, or any other edge proxy, because
the right choice depends on how you are exposing the server to the internet.
You will add a proxy (or a Cloudflare Tunnel) in a separate, later step.

### The three production containers

```
                                     +-------------------+
   [edge proxy: nginx-on-host        |                   |
    OR cloudflared OR similar]       |                   |
                  |                  |                   |
                  v                  |                   |
         localhost:APP_PORT    -->   |  app (Spring Boot)|
                                     |                   |
                                     +---------+---------+
                                               |
                                               v (internal Docker network)
                                     +-------------------+         +-------------------+
                                     |  db (PostgreSQL)  | <-----  |      backup       |
                                     +-------------------+         | (nightly pg_dump) |
                                                                   +-------------------+
```

| Container | Role |
|-----------|------|
| **db** | PostgreSQL 16, image pinned by digest. Data lives in the named Docker volume `pgdata`. **No host port published** (unlike the dev compose file, which publishes 5433). |
| **app** | Spring Boot application serving the API and the React SPA at `/`. Image is `${WEALTHVIEW_IMAGE:-ghcr.io/jakefearsd/wealthview}:${WEALTHVIEW_VERSION}` — `WEALTHVIEW_VERSION` is mandatory, and the compose file rejects an empty value with an explicit error. Publishes `${APP_PORT:-80}:8080` on the host — this is what your edge proxy or Cloudflare Tunnel points at. Runs as the non-root user `wv` with a container-level HEALTHCHECK against `/actuator/health`. |
| **backup** | Alpine 3.20 container with `postgresql16-client`, running `crond`. Executes `pg_dump -Fc` at **03:00 daily** (container timezone, UTC by default) into a `backups/` directory bind-mounted next to the compose file. Prunes dumps older than `BACKUP_RETENTION_DAYS` (default 14). |

Nothing listens on the public internet directly — you only expose the edge
proxy, never the container port. See [Step 8](#step-8-choose-and-configure-an-edge-proxy-tls).

### The app image is pulled, not built

CI (`backend-verify.yml`) publishes the release image to GitHub Container
Registry on every `v*` tag, after the unit tests, quality gates and the full
integration suite pass, and cuts a GitHub Release alongside it. The server
pulls that artifact:

```
ghcr.io/<owner>/wealthview:<version>
```

`docker-compose.prod.yml` deliberately has **no `build:` key** for the `app`
service. The production host therefore needs neither the source tree nor a JDK
— building on the server meant deploying something no CI job had ever verified.
`WEALTHVIEW_IMAGE` exists only so a fork, a private mirror, or an air-gapped
registry can be pointed at without editing the compose file.

> **The first CI push creates the GHCR package as private, even for a public
> repository.** Until you change that, every host pulling it must
> `docker login ghcr.io` with a `read:packages` Personal Access Token. Making
> the package public once (repo → **Packages** → the package → **Package
> settings** → **Change visibility**) is the simpler answer for a public repo.
> See [upgrading.md](upgrading.md#before-your-first-pull-registry-access).

`deploy.sh` still exists for the build-here-ship-there case, but it predates
this flow and takes no pre-deploy backup and does no health-checked rollback.
For a host you can SSH into, prefer `wv update`. See
[Appendix B](#appendix-b--deploysh-build-here-run-there).

---

## VPS / host requirements

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| OS | Linux **x86-64** with a recent kernel | Ubuntu 24.04 LTS, Debian 12 |
| CPU | 1 vCPU | 2+ vCPU |
| RAM | 1 GB | 2 GB |
| Disk | 10 GB | 20+ GB (room for backups and Docker images) |
| Network | Outbound HTTPS to `ghcr.io` to pull the image; inbound depends on edge proxy choice | Static IPv4 address if exposing directly |

**The published image is `linux/amd64` only.** The `Dockerfile` compiles the
whole Maven backend inside the build stage, so an emulated `arm64` build would
run the entire reactor under QEMU and take 30–60+ minutes; CI does not pay
that. An ARM host — a Raspberry Pi, an Ampere VPS, Apple silicon — cannot pull
and run the release image. Running WealthView there means building your own
image on that machine and deploying it with `wv update --no-pull`, which is a
path you maintain yourself.

For a home-lab install with no public IP, an x86-64 mini-PC behind a Cloudflare
Tunnel is a good target. See [cloudflared.md](cloudflared.md).

Command-line tools `wv` expects on the host: `docker` (with the Compose
plugin), `curl`, `python3`, `openssl`. Optional: `age` (encrypted backups and
host migration), `rsync` or the `aws` CLI (off-host backup copies), `gitleaks`.
No JDK, Maven, Node or npm is needed — those live only in the image build.
`./wv config-check` tells you exactly which are missing.

---

## Step 1: Install Docker

If Docker is not already installed, follow the
[official Docker Engine install guide](https://docs.docker.com/engine/install/)
for your distribution. On Debian/Ubuntu, the short version is:

```bash
# Install prerequisites
sudo apt-get update
sudo apt-get install -y ca-certificates curl

# Add Docker's official GPG key and repo
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker Engine + Compose plugin
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Verify both `docker` and `docker compose` (Compose v2, two words, not
`docker-compose`) are available:

```bash
docker --version            # Docker version 24.x or newer
docker compose version      # Docker Compose version v2.x
```

Add your user to the `docker` group so you don't need `sudo` for every
command. **You must log out and back in for this to take effect.**

```bash
sudo usermod -aG docker $USER
# Now log out, log back in, and re-run:
docker ps
# If you see an empty table (no error), you're good.
```

---

## Step 2: Get the deployment files

The server does **not** run from the source tree — it runs the published image.
What it actually needs is a small set of files:

```
docker-compose.prod.yml    # the stack definition
.env                       # secrets and the version pin (you create this in Step 3)
infra/backup/              # Dockerfile + scripts for the nightly-backup sidecar
bin/wv, bin/wv-lib/        # the admin tool (Step 4)
```

Cloning the repo is simply the easiest way to obtain them, and it keeps
`CHANGELOG.md` and the docs on the box. Pick where the app will live;
`/opt/wealthview` is a common choice on Linux servers.

```bash
sudo mkdir -p /opt/wealthview
sudo chown $USER /opt/wealthview
cd /opt/wealthview

git clone https://github.com/<your-org>/wealthview.git .
```

Replace `<your-org>` with the GitHub user or organization hosting the fork you
are deploying.

The checkout does not decide which version runs — `WEALTHVIEW_VERSION` in
`.env` does. Checking out the matching release tag simply keeps the compose
file and the docs on the host in step with the image you are deploying:

```bash
git tag -l | sort -V | tail -5
git checkout v1.2.5
```

If you would rather not keep a checkout at all, download
`docker-compose.prod.yml`, `.env.example`, `infra/`, and `bin/` from the release
and place them under `/etc/wealthview` as described in
[Step 4](#step-4-install-the-wv-admin-tool). Nothing in the update path needs
git afterwards.

---

## Step 3: Create the `.env` file

All runtime secrets live in `.env` next to the compose file. A template is
provided:

```bash
cp .env.example .env
```

### Required — the stack will not start without these

`docker-compose.yml` uses `${VAR:?message}` syntax for all four, and
`./wv` re-checks them (including refusing any value still set to `CHANGE_ME`)
before it will bring the stack up.

```dotenv
# Database password for the wv_app PostgreSQL role.
DB_PASSWORD=<generate with: openssl rand -base64 24>

# HMAC-SHA256 signing key for JWT access/refresh tokens. Must be 32+ chars.
JWT_SECRET=<generate with: openssl rand -base64 48>

# Initial password for admin@wealthview.local. Change immediately after first login.
SUPER_ADMIN_PASSWORD=<generate with: openssl rand -base64 18>

# Base64-encoded 32-byte AES-256-GCM key that encrypts TOTP MFA secrets at rest.
MFA_ENCRYPTION_KEY=<generate with: openssl rand -base64 32>
```

### Required for production specifically

```dotenv
# The release to pull. docker-compose.prod.yml aborts if this is empty.
# Setting it also flips ./wv into prod mode (it starts using
# docker-compose.prod.yml instead of docker-compose.yml).
# NEVER use `latest` — CI publishes that tag as a convenience pointer, but
# deploying it defeats `wv rollback`, which recovers by re-pinning the tag that
# was running before the update.
WEALTHVIEW_VERSION=1.2.5

# Allowed origin for /api/* requests. REQUIRED on the prod profile — the app
# refuses to start with an empty or non-https:// value. Set to your public URL.
CORS_ORIGIN=https://wealthview.example.com
```

Optionally, point at a different registry. Leave it unset to use the upstream
GHCR package:

```dotenv
# Only for a fork, a private mirror, or an air-gapped registry.
# WEALTHVIEW_IMAGE=ghcr.io/jakefearsd/wealthview
```

### Optional

```dotenv
# Finnhub API key for live stock prices. Leave blank to use seeded prices only.
# When blank, PriceSyncService is not created at all.
FINNHUB_API_KEY=

# Enable the weekly Zillow property valuation sync. Default false.
ZILLOW_ENABLED=false

# Host port bound to the container's 8080. Default 80. Set to a non-privileged
# port (e.g. 8080) when an edge proxy on the same box takes port 80.
APP_PORT=80

# Days of nightly pg_dump files the backup container keeps. Default 14.
BACKUP_RETENTION_DAYS=14

# age public key used by `./wv backup --encrypt` and `./wv migrate-out`.
# BACKUP_ENCRYPTION_RECIPIENT=age1...

# Matching age identity file used by `./wv restore` / `./wv verify` to decrypt.
# BACKUP_ENCRYPTION_KEY_FILE=/etc/wealthview/backup.key

# Off-host destination for `./wv backup --remote`. s3://, rsync://, or user@host:/path.
# BACKUP_REMOTE_DEST=
```

### What validates what

1. **Compose** refuses to interpolate a missing `DB_PASSWORD`, `JWT_SECRET`,
   `SUPER_ADMIN_PASSWORD`, `MFA_ENCRYPTION_KEY`, or `WEALTHVIEW_VERSION`.
2. **`./wv config-check` / `./wv up`** refuse to proceed while any of the four
   secrets is empty or still literally `CHANGE_ME`.
3. **`ProductionConfigValidator`** (active on the `prod` and `docker` profiles)
   runs at application startup and aborts on: a JWT secret shorter than 32
   characters, any known development default (`admin123`, `demo123`, the two
   historical JWT defaults, the dev MFA key), any value starting with
   `LOCAL_DEV_`, an empty `CORS_ORIGIN`, a `CORS_ORIGIN` entry not beginning
   with `https://` under `prod`, and `SPRING_PROFILES_ACTIVE` combining `prod`
   with `dev` or `docker`.

Generate strong values on any Linux box:

```bash
openssl rand -base64 24    # DB_PASSWORD
openssl rand -base64 48    # JWT_SECRET
openssl rand -base64 18    # SUPER_ADMIN_PASSWORD
openssl rand -base64 32    # MFA_ENCRYPTION_KEY
```

Lock down the file so only your user can read it:

```bash
chmod 600 .env
ls -la .env                # should show: -rw------- 1 you you ...
```

**Never commit `.env`** to git. It is already in `.gitignore`; verify:

```bash
grep -E '^\.env$' .gitignore
```

---

## Step 4: Install the `wv` admin tool

`wv` is the single command surface for every routine operation: start and stop
the stack, take and verify backups, upgrade with auto-rollback, restore,
migrate hosts, rotate secrets. The dispatcher is `bin/wv`, with one library per
subcommand under `bin/wv-lib/`. `./wv` at the repo root is a thin shim.

You can use it straight from the checkout with no configuration at all:

```bash
./wv help          # full operator man page
./wv <sub> --help  # per-subcommand detail
```

From a source tree it auto-detects mode: `WEALTHVIEW_VERSION` set in `.env`
means prod (`docker-compose.prod.yml`), unset means dev
(`docker-compose.yml`).

### Installing it system-wide

Put it on `PATH` so you can run `wv status` from anywhere on the box:

```bash
# 1. Lay down the binaries (from your source checkout or release tar).
sudo install -m 0755 bin/wv /usr/local/bin/wv
sudo install -d -m 0755 /usr/local/lib/wv-lib
sudo install -m 0644 bin/wv-lib/*.sh /usr/local/lib/wv-lib/
sudo ln -snf /usr/local/lib/wv-lib /usr/local/bin/wv-lib
# (the dispatcher locates wv-lib next to itself, hence the symlink)

# 2. Lay down the config.
sudo install -d -m 0755 /etc/wealthview
sudo cp docker-compose.prod.yml /etc/wealthview/
sudo cp -r infra /etc/wealthview/
sudo cp .env /etc/wealthview/.env
sudo chmod 0600 /etc/wealthview/.env
sudo install -d -m 0755 /var/lib/wealthview/backups

# 3. Write the config file.
sudo cp bin/wv.conf.example /etc/wealthview/wv.conf
sudo $EDITOR /etc/wealthview/wv.conf

# 4. Verify everything resolves.
sudo wv config-check

# 5. Bring the stack up.
sudo wv up
```

`/etc/wealthview/wv.conf` is shell-syntax `KEY=VALUE` only — `wv` sniffs each
line and refuses to source a file containing anything else. **Never put real
secrets in it**; secrets belong in the env file it points at. Recognised keys
(all optional, sensible defaults apply):

| Key | Meaning |
|---|---|
| `WV_COMPOSE_FILE` | Absolute path to the compose file. |
| `WV_COMPOSE_OVERRIDE_FILE` | Optional second `-f` for `docker compose`. |
| `WV_ENV_FILE` | Absolute path to the env file. Default `<repo>/.env`. |
| `WV_BACKUPS_DIR` | Where `wv backup` writes and `wv backups` looks. Default `<repo>/backups`. |
| `WV_COMPOSE_PROJECT` | Compose project name. Default `wealthview`. |
| `WV_APP_PORT` | Host port used to build the health URL. Default 80. |
| `WV_HEALTH_URL` | Full health URL; overrides the constructed default. |
| `WV_PREVIOUS_IMAGE_FILE` | Where `wv update` records the prior tag for `wv rollback`. |
| `WV_HOST` | `user@host` to drive a remote Docker daemon over `DOCKER_HOST=ssh://…`. |

A minimal `/etc/wealthview/wv.conf`:

```
WV_COMPOSE_FILE=/etc/wealthview/docker-compose.prod.yml
WV_ENV_FILE=/etc/wealthview/.env
WV_BACKUPS_DIR=/var/lib/wealthview/backups
WV_COMPOSE_PROJECT=wealthview
WV_APP_PORT=80
```

The config file is resolved in this order, first match wins: `--config FILE` →
`$WV_CONFIG_FILE` → `/etc/wealthview/wv.conf` →
`$XDG_CONFIG_HOME/wealthview/wv.conf` → `~/.config/wealthview/wv.conf` →
source-tree fallback.

> **Two things to know when `/etc/wealthview` is the compose directory.**
>
> 1. The `app` service is image-only — it has no `build:` key, so nothing about
>    it needs a `Dockerfile` on the host. The `backup` sidecar still declares
>    `build: ./infra/backup`, relative to the compose file, which is why step 2
>    above copies `infra/` across. `wv up` passes `--build`, and in prod that
>    builds the `backup` image and nothing else.
> 2. The `backup` container bind-mounts `./backups` — that resolves to
>    `/etc/wealthview/backups`, **not** the `WV_BACKUPS_DIR` you set in
>    `wv.conf`. Either point `WV_BACKUPS_DIR` at `/etc/wealthview/backups` so
>    on-demand and cron'd dumps share a directory, or symlink one to the other.

---

## Step 5: Start the stack

```bash
./wv up
```

`./wv up` validates `.env`, runs `docker compose -f docker-compose.prod.yml up
--build -d`, then polls `http://localhost:${APP_PORT}/actuator/health` for up
to 120 seconds. The first build takes 2–5 minutes (it downloads Maven and npm
dependencies); subsequent builds reuse Docker's layer cache and take under a
minute.

Watch the logs until the app finishes starting up:

```bash
./wv logs app
```

You should see:

- `Successfully applied N migrations to schema "public"` (Flyway)
- `Started WealthviewApplication in <seconds>` (Spring Boot is up)
- `Production security configuration validated successfully`
- `SuperAdminInitializer` creating the `admin@wealthview.local` account

Press `Ctrl-C` to stop tailing (the containers keep running).

---

## Step 6: Verify the app is healthy

```bash
./wv status
```

This prints the detected mode, the compose file in use, `docker compose ps`,
and a one-shot health probe. Expect three rows (`db`, `app`, `backup`) all
showing `running`; the `app` row also shows `(healthy)` once the
container-level HEALTHCHECK passes, which can take 30–60 seconds after startup.

Load the UI in a browser at `http://<server-ip>:${APP_PORT}/` — you should see
the WealthView login page. Don't log in yet; first put TLS in front of the app.

---

## Step 7: Confirm backups are wired up

The `backup` container runs `pg_dump -Fc` at 03:00 daily and writes
`wealthview_auto_<YYYY-MM-DD_HH-MM>.dump` into the bind-mounted `backups/`
directory next to the compose file. The `auto_` marker is what retention keys
off, so the sweep can never reach an operator's `./wv backup` dump. Trigger one immediately to confirm it
works, then take an on-demand `wv` backup and verify it restores:

```bash
docker compose -f docker-compose.prod.yml exec backup /backup.sh
./wv backups                                    # list with size + age
./wv backup --label first-run                   # on-demand pg_dump
./wv verify backups/wealthview_<ts>_first-run.dump
```

`./wv verify` restores the dump into a throwaway `postgres:16` container with a
random password, runs sanity queries, and tears it down — it never touches the
live database. Schedule it weekly.

To encrypt backups (strongly recommended for anything leaving the host):

```bash
age-keygen -o /etc/wealthview/backup.key
sudo chmod 600 /etc/wealthview/backup.key
grep '# public key:' /etc/wealthview/backup.key   # copy the age1... value
```

Put the public key in `BACKUP_ENCRYPTION_RECIPIENT` and the identity path in
`BACKUP_ENCRYPTION_KEY_FILE`, then use `./wv backup --encrypt`. Note that
`--encrypt` applies to `wv`-initiated backups only; the cron'd `backup`
container writes plaintext dumps. Full detail:
[operations.md](operations.md#on-demand-backup).

---

## Step 8: Choose and configure an edge proxy (TLS)

The container is serving plain HTTP. You must not let unauthenticated traffic
reach it over the public internet. Pick one of the three options below.

### Option A — Cloudflare Tunnel (recommended for home labs / self-hosters)

Best when: your server is behind NAT, has no public IP, or you don't want to
manage certificates yourself. Cloudflare terminates TLS at their edge and
pushes traffic to your server through an outbound tunnel; you never open any
inbound ports.

Follow [cloudflared.md](cloudflared.md) for the step-by-step.

### Option B — nginx + Let's Encrypt on the same host

Best when: you have a public IP and want to stay off third-party infrastructure.
nginx handles TLS termination, certbot renews certificates automatically.

Follow [tls-and-nginx.md](tls-and-nginx.md).

### Option C — Existing reverse proxy (Caddy, Traefik, etc.)

If you already run a reverse proxy, point it at `http://localhost:${APP_PORT}`.
You need to:

1. Terminate TLS at the proxy.
2. Forward `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto` headers.
3. Wire `APP_RATE_LIMIT_TRUSTED_PROXIES` through to the app container (see
   [Trusted proxy configuration](#trusted-proxy-configuration) below).

### After the edge proxy is up

Regardless of which option you chose, verify HTTPS works end-to-end:

```bash
curl -s https://wealthview.example.com/actuator/health
# {"status":"UP"}

curl -sI https://wealthview.example.com/ | grep -Ei \
  "strict-transport|permissions-policy|x-frame-options|content-security-policy"
```

The app emits these itself from `SecurityConfig`:

| Header | Value |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000 ; includeSubDomains ; preload` |
| `X-Frame-Options` | `DENY` |
| `X-Content-Type-Options` | `nosniff` |
| `Permissions-Policy` | `geolocation=(), microphone=(), camera=(), payment=()` |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'` |

---

## Step 9: First login

Open `https://wealthview.example.com/` in a browser.

Log in as super admin:

- **Email:** `admin@wealthview.local`
- **Password:** the `SUPER_ADMIN_PASSWORD` you set in `.env`

No demo tenant or demo user is seeded on the `prod` profile —
`SampleDataInitializer` runs only on `dev` and `docker`, and
`DevDataInitializer` only on `dev`. From the admin console you can:

1. Create your first tenant (**Admin → Tenants → New**).
2. Issue an invite code for that tenant — copy the code.
3. Open `/register` in a private browser window and sign up a regular user
   account against that invite code. That user becomes the first non-admin
   member of the tenant.

Full lifecycle for tenants, invite codes, and the audit log:
[`docs/administration/tenant-and-user-management.md`](../administration/tenant-and-user-management.md).

---

## Trusted proxy configuration

The app reads the client IP from `X-Forwarded-For` for rate limiting and login
audit logging — but **only** when the request arrives from a peer listed in the
`app.rate-limit.trusted-proxies` property (`ClientIpResolver`). Otherwise the IP
is read straight from the TCP connection, which will be the proxy's own IP, not
the end user's.

The property defaults to empty and is **not** in `.env.example` or in either
compose file's `environment:` block. Compose only passes the variables it
explicitly lists, so adding `APP_RATE_LIMIT_TRUSTED_PROXIES=…` to `.env` on its
own has no effect. Wire it through explicitly — either edit the `app` service in
`docker-compose.prod.yml`:

```yaml
services:
  app:
    environment:
      # ...existing entries...
      APP_RATE_LIMIT_TRUSTED_PROXIES: ${APP_RATE_LIMIT_TRUSTED_PROXIES:-}
```

…or add the same block in a Compose override file and point
`WV_COMPOSE_OVERRIDE_FILE` at it. Then set the value in `.env`:

```dotenv
# If nginx runs on the same host:
APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1
```

Leave it unset if nothing is in front of the app. If all your login-activity
rows show the same internal IP, this is why.

---

## Step 10: Turn on optional integrations

### Finnhub live stock prices

1. Sign up at [finnhub.io](https://finnhub.io/) for a free API key.
2. Add it to `.env`:
   ```dotenv
   FINNHUB_API_KEY=your_api_key_here
   ```
3. Restart the app so Spring picks up the new env var:
   ```bash
   docker compose -f docker-compose.prod.yml up -d app
   ```

The price sync runs once per weekday in `America/New_York`, on
`PriceSyncService`'s own schedule (`app.finnhub.sync-cron`, default
`0 0 18 * * MON-FRI`). With no key, the `PriceSyncService` bean is not created
and the schedule does not exist. The free tier's 60 calls/minute is plenty for any personal
portfolio; the client self-throttles at `app.finnhub.rate-limit-ms` (1100 ms).

Stock splits are auto-detected by a separate daily job
(`app.stock-splits.sync-cron`, default `0 0 2 * * *` America/New_York), which
also requires a Finnhub key. See
[`docs/operations/stock-splits.md`](../operations/stock-splits.md).

### Zillow property valuations

1. Set `ZILLOW_ENABLED=true` in `.env` (both compose files map this to
   `APP_ZILLOW_ENABLED`, which Spring binds to `app.zillow.enabled`).
2. Restart the app:
   ```bash
   docker compose -f docker-compose.prod.yml up -d app
   ```
3. In the UI, configure a Zillow ZPID for each property.

The sync runs on `app.zillow.sync-cron`, default `0 0 6 * * SUN` — 06:00 on
Sundays in the container's timezone (UTC unless you change it).

---

## Optional: observability stack

`docker-compose.observability.yml` layers Prometheus 3.13.2 and Grafana 13.1.3
on top of the production stack:

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.observability.yml up -d
```

It needs one extra variable, which has no fallback:

```dotenv
GRAFANA_ADMIN_PASSWORD=<generate with: openssl rand -base64 18>
```

`/actuator/prometheus` and `/actuator/metrics` require the `SUPER_ADMIN` role
by default, and Prometheus has no way to satisfy that: the app authenticates a
JWT only — HTTP Basic was never enabled and there is no `UserDetailsService` —
so a scraper has no credential it can present. The overlay therefore opts the
two endpoints out of authentication instead, setting
`APP_OBSERVABILITY_ANONYMOUS_METRICS: "true"` on the app service.
`infra/observability/prometheus.yml` then scrapes `app:8080` with no
credentials at all.

> **This means `/actuator/prometheus` and `/actuator/metrics` are
> unauthenticated on any deployment running this overlay.** They are reachable
> only on the compose network — the overlay does not publish the app's port —
> and you should keep it that way: do not publish the app port to the internet,
> and block `/actuator` at the reverse proxy (see
> [Security hardening](security-hardening.md)). Metrics carry no tenant or user
> identity, but they do expose request URIs and volumes. The flag defaults to
> `false`; if you would rather keep the endpoints gated, leave it off and put a
> proxy that injects a `SUPER_ADMIN` bearer token in front of the scrape.
> The rest of `/actuator/**` stays `SUPER_ADMIN`-only either way.

Ports default to 9090 (Prometheus) and 3000 (Grafana), overridable via
`PROMETHEUS_PORT` and `GRAFANA_PORT`. Grafana ships a pre-provisioned
datasource and the dashboard at
`infra/observability/grafana/dashboards/wealthview.json`; alert rules live in
`infra/observability/prometheus-rules.yml`. Neither port should be exposed to
the internet.

More detail: [`docs/OBSERVABILITY.md`](../OBSERVABILITY.md).

---

## Day-2 operations

The following topics each have their own guide. Read them once your instance
is up and running:

| Task | Guide |
|------|-------|
| Every `wv` subcommand, end to end | [`operations.md`](operations.md) |
| Verify backups, test restore, set up offsite copies | [`docs/administration/backups.md`](../administration/backups.md) |
| Monitor logs, set up Prometheus scraping, alerting | [`docs/administration/monitoring-and-logging.md`](../administration/monitoring-and-logging.md) |
| Tune Postgres, JVM heap, disk capacity | [`docs/administration/maintenance.md`](../administration/maintenance.md) |
| Upgrade to a new WealthView version | [`upgrading.md`](upgrading.md) |
| Harden the host OS and container runtime | [`security-hardening.md`](security-hardening.md) |
| Diagnose problems | [`docs/administration/troubleshooting.md`](../administration/troubleshooting.md) |
| Full environment-variable reference | [`docs/reference/configuration.md`](../reference/configuration.md) |

---

## Quick command reference

```bash
# Lifecycle
./wv up                     # start, wait for health (prod: builds only the backup sidecar)
./wv down                   # stop; preserve the pgdata volume
./wv restart                # down then up
./wv status                 # compose ps + one-shot health probe
./wv logs app               # tail one service (--tail N, --no-follow)

# Backups
./wv backup --encrypt --label pre-change
./wv backups                # list with size + age
./wv verify <file>          # round-trip through a throwaway postgres
./wv restore <file>         # confirm, stop app, pg_restore, restart, health-check

# Upgrades — set WEALTHVIEW_VERSION in .env first
./wv update                 # pre-update backup -> pull -> swap -> auto-rollback
./wv rollback               # revert to the image recorded by the last update

# Other
./wv psql                   # interactive psql as wv_app
./wv config-check           # validate .env, compose files, tools
./wv rotate-secret JWT_SECRET
./wv help                   # full man page

# DANGER — deletes the database volume (everything is gone)
./wv down --with-volumes
```

---

## Appendix A — file and port map

| Path / port | Purpose |
|---|---|
| `./.env` | All runtime secrets. `chmod 600`. Never commit — it is in `.gitignore`. |
| `./backups/` | `pg_dump` output. Bind-mounted into the `backup` container; also where `wv backup` writes by default. Gitignored. |
| `./infra/backup/` | `Dockerfile` + `backup.sh` + `restore.sh` + `crontab` for the backup container. Tracked in the repo. |
| `./infra/observability/` | Prometheus config + rules and Grafana provisioning/dashboards. |
| `./.wv-previous-image` | Full image reference (`repository:tag`) recorded by `wv update` for `wv rollback`. Path overridable via `WV_PREVIOUS_IMAGE_FILE`. |
| `pgdata` (named volume) | PostgreSQL data directory. Survives `./wv down`; destroyed by `./wv down --with-volumes`. |
| Container `8080` | Spring Boot HTTP listener. Mapped to host `${APP_PORT}` (default 80) by the prod compose file, and to host 80 unconditionally by the dev one. |
| Container `5432` | PostgreSQL. **Not published** by `docker-compose.prod.yml`; published as host 5433 by `docker-compose.yml`. |

---

## Appendix B — `deploy.sh` (build here, run there)

`deploy.sh` at the repo root handles the case where you build the image on a
workstation and ship it to a server that has no source tree. It **predates the
registry flow** and is not the recommended path: for any host you can SSH into,
`wv update` is better in every way (pre-deploy backup, health check,
auto-rollback).

> **It needs one extra variable now.** `deploy.sh` builds and loads the image as
> `wealthview:<version>`, while `docker-compose.prod.yml` resolves
> `${WEALTHVIEW_IMAGE:-ghcr.io/jakefearsd/wealthview}:${WEALTHVIEW_VERSION}`.
> Unless the remote `.env` also sets `WEALTHVIEW_IMAGE=wealthview`, compose
> will not find the loaded image and will try to pull from GHCR instead.

```bash
DEPLOY_HOST=you@192.168.1.50 ./deploy.sh
# optional: DEPLOY_DIR=/opt/wealthview (default)
```

What it does:

1. Resolves a version tag — `$WEALTHVIEW_VERSION`, else `git describe
   --tags --always --dirty`, else the short SHA — and builds
   `wealthview:<version>` locally. It deliberately never tags `:latest`.
2. `docker save | gzip` into `/tmp/wealthview-image.tar.gz`.
3. Creates `$DEPLOY_DIR` on the remote, `scp`s `docker-compose.prod.yml` there
   **as `docker-compose.yml`**, and copies `infra/` alongside it.
4. If the remote has no `.env`, copies `.env.example` across and stops with
   instructions. Fill in `DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD`,
   `MFA_ENCRYPTION_KEY`, `CORS_ORIGIN`, and `WEALTHVIEW_VERSION`, then re-run.
5. `scp`s the tarball, `docker load`s it, and runs `docker compose down` then
   `up -d` on the remote with `WEALTHVIEW_VERSION` set.

Because the file lands as `docker-compose.yml`, subsequent `wv` invocations on
that host should point `WV_COMPOSE_FILE` at `$DEPLOY_DIR/docker-compose.yml`.
`deploy.sh` does not take a pre-deploy backup and does not health-check with
rollback — for routine upgrades on a host you can SSH into, prefer
[`./wv update`](upgrading.md).

A closer equivalent that keeps the safety rails is to move the *published*
image by hand and skip the fetch — see
[Upgrading a host with no source tree](upgrading.md#upgrading-a-host-with-no-source-tree).

---

## Related guides

- [Quick Start](quickstart.md) — 5-minute local evaluation.
- [Operations Handbook](operations.md) — the full `wv` command surface.
- [Cloudflared Deployment](cloudflared.md) — step-by-step Cloudflare Tunnel setup.
- [TLS and Nginx](tls-and-nginx.md) — nginx + Let's Encrypt on the host.
- [Security Hardening](security-hardening.md) — firewall, SSH, secrets, app-level security.
- [Upgrading](upgrading.md) — keep your instance up to date.
