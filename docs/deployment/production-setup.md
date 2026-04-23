[<- Back to README](../../README.md)

# Production Setup

This is the end-to-end guide for putting WealthView on a real machine that you
intend to use every day. It assumes only basic Docker knowledge (you have run
`docker compose up` once before) and walks through every step.

At the end you will have:

- WealthView running in Docker on a Linux host.
- A PostgreSQL database with nightly automatic backups.
- An edge proxy providing TLS (HTTPS) — you pick one of two options.

For a 5-minute local trial, see [quickstart.md](quickstart.md) instead.

---

## What you are about to deploy

WealthView ships with **two** Compose files at the repo root:

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Local evaluation. Two services (`db`, `app`) with the `docker` Spring profile, which seeds a demo tenant. Not for production. |
| `docker-compose.prod.yml` | Production. Three services: `db`, `app`, `backup`. Runs the `prod` Spring profile with strict config validation and no seed data. |

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
| **db** | PostgreSQL 16. Data lives in the named Docker volume `pgdata`. No host port published. |
| **app** | Spring Boot application serving the API and the React SPA at `/`. Publishes `${APP_PORT:-80}:8080` on the host — this is what your edge proxy or Cloudflare Tunnel points at. Runs as a non-root user (`wv`) with a container-level HEALTHCHECK against `/actuator/health`. |
| **backup** | Alpine container running `cron` + `pg_dump`. Writes timestamped dumps into `./backups/` on the host every night. |

Nothing listens on the public internet directly — you only expose the edge
proxy, never the container port. See [Step 6](#step-6-choose-and-configure-an-edge-proxy-tls).

---

## VPS / host requirements

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| OS | Any Linux (x86-64 or ARM64) with a recent kernel | Ubuntu 24.04 LTS, Debian 12 |
| CPU | 1 vCPU | 2+ vCPU |
| RAM | 1 GB | 2 GB |
| Disk | 10 GB | 20+ GB (room for backups + Docker images) |
| Network | Outbound HTTPS for Maven/npm builds; inbound depends on edge proxy choice | Static IPv4 address if exposing directly |

For a home-lab install (no public IP), a Raspberry Pi 4 with 4 GB RAM behind a
Cloudflare Tunnel is a perfectly good target. See
[cloudflared.md](cloudflared.md).

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

Verify both `docker` and `docker compose` (Compose v2, one word, not
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

## Step 2: Clone the repository

Pick where the app will live. `/opt/wealthview` is a common choice on Linux
servers; anywhere writable by your user works.

```bash
sudo mkdir -p /opt/wealthview
sudo chown $USER /opt/wealthview
cd /opt/wealthview

git clone https://github.com/<your-org>/wealthview.git .
```

Replace `<your-org>` with the GitHub user or organization hosting the fork you
are deploying.

---

## Step 3: Create the `.env` file

All runtime secrets live in `.env` at the repo root. A template is provided:

```bash
cp .env.example .env
```

Edit `.env` and fill in **every** value that currently reads `CHANGE_ME`:

```dotenv
# Database password for the wv_app PostgreSQL role.
DB_PASSWORD=<generate with: openssl rand -base64 24>

# HMAC-SHA256 signing key for JWT access/refresh tokens. Must be 32+ chars.
JWT_SECRET=<generate with: openssl rand -base64 48>

# Initial password for admin@wealthview.local. Change immediately after first login.
SUPER_ADMIN_PASSWORD=<generate with: openssl rand -base64 18>

# Finnhub API key for live stock prices. Optional — leave blank to use seeded prices only.
FINNHUB_API_KEY=

# Enable the weekly Zillow property valuation sync. Optional, default false.
ZILLOW_ENABLED=false

# Keep this many days of nightly pg_dump files under ./backups/. Optional, default 14.
BACKUP_RETENTION_DAYS=14

# Allowed origin for /api/* requests. REQUIRED in production — the app refuses to
# start with an empty or non-https value on the prod profile. Set to your public URL.
CORS_ORIGIN=https://wealthview.example.com

# Host port the container listens on. Leave as 80 when the app is behind a local
# edge proxy on the same machine, or set to a non-privileged port (e.g. 8080) when
# behind Cloudflare Tunnel or another loopback-only proxy.
APP_PORT=80
```

Three things are mandatory in production:

1. `DB_PASSWORD`, `JWT_SECRET`, `SUPER_ADMIN_PASSWORD` cannot be the dev
   defaults — `ProductionConfigValidator` aborts startup with a clear error
   message if they are.
2. `JWT_SECRET` must be at least 32 characters.
3. `CORS_ORIGIN` must be a non-empty `https://...` URL on the `prod` profile
   (again, the validator enforces this). The only exception is the `docker`
   profile, which also allows `http://localhost` for local evaluation.

Generate strong values on any Linux box:

```bash
openssl rand -base64 24    # DB_PASSWORD
openssl rand -base64 48    # JWT_SECRET
openssl rand -base64 18    # SUPER_ADMIN_PASSWORD
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

## Step 4: Start the stack

```bash
docker compose -f docker-compose.prod.yml up --build -d
```

What this command does, line by line:

- `docker compose` — invoke the Compose plugin.
- `-f docker-compose.prod.yml` — use the production compose file (not the
  default `docker-compose.yml`).
- `up` — create and start every service defined in the file.
- `--build` — build the `app` and `backup` container images from source before
  starting. The first build takes 2–5 minutes (downloads Maven + npm
  dependencies). Subsequent builds reuse Docker's layer cache and take under a
  minute.
- `-d` — detach; run in the background.

Watch the logs until the app finishes starting up:

```bash
docker compose -f docker-compose.prod.yml logs -f app
```

You should see:

- `Successfully applied N migrations to schema "public"` (Flyway)
- `Started WealthviewApplication in <seconds>` (Spring Boot is up)
- `SuperAdminInitializer` creating the `admin@wealthview.local` account

Press `Ctrl-C` to stop tailing (the containers keep running).

---

## Step 5: Verify the app is healthy

Check all three containers are running:

```bash
docker compose -f docker-compose.prod.yml ps
```

Expected output: three rows (`db`, `app`, `backup`) all showing `running` (the
`app` row also shows `(healthy)` once the container-level HEALTHCHECK passes,
which can take 30–60 seconds after startup).

Hit the public health endpoint directly on the container port:

```bash
curl -s http://localhost/actuator/health
# {"status":"UP"}
```

Load the UI in a browser at `http://<server-ip>/` — you should see the
WealthView login page. Don't log in yet; first put TLS in front of the app.

---

## Step 6: Choose and configure an edge proxy (TLS)

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

If you already run a reverse proxy, point it at `http://localhost:${APP_PORT}`
(usually `http://localhost:80`). You need to:

1. Terminate TLS at the proxy.
2. Forward `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto` headers.
3. Set `APP_RATE_LIMIT_TRUSTED_PROXIES` in `.env` to the proxy's peer IP (see
   [Trusted proxy configuration](#trusted-proxy-configuration) below).

### After the edge proxy is up

Regardless of which option you chose, verify HTTPS works end-to-end:

```bash
curl -s https://wealthview.example.com/actuator/health
# {"status":"UP"}

curl -sI https://wealthview.example.com/ | grep -Ei "strict-transport|permissions-policy|x-frame-options"
# Should show: HSTS, Permissions-Policy, X-Frame-Options headers
```

---

## Step 7: First login

Open `https://wealthview.example.com/` in a browser.

Log in as super admin:

- **Email:** `admin@wealthview.local`
- **Password:** the `SUPER_ADMIN_PASSWORD` you set in `.env`

From the admin console you can:

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
audit logging — but **only** when the request arrives from a peer listed in
`APP_RATE_LIMIT_TRUSTED_PROXIES`. Otherwise the IP is read straight from the
TCP connection (which will be the proxy's own IP, not the end user's).

Set this to a comma-separated list of proxy IPs in `.env`:

```dotenv
# If nginx runs on the same host:
APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1

# If behind Cloudflare Tunnel:
APP_RATE_LIMIT_TRUSTED_PROXIES=<cloudflared container IP or 127.0.0.1>
```

Leave this **empty** if nothing is in front of the app. See the troubleshooting
table in [`docs/DeploymentGuide.md`](../DeploymentGuide.md#7-troubleshooting)
if all your login activity rows show the same internal IP.

---

## Step 8: Turn on optional integrations

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

The sync runs weekdays at 16:30 America/New_York. The free tier allows 60
API calls per minute — enough for any personal portfolio.

### Zillow property valuations

1. Set `ZILLOW_ENABLED=true` in `.env`.
2. Restart the app:
   ```bash
   docker compose -f docker-compose.prod.yml up -d app
   ```
3. In the UI, configure a Zillow ZPID for each property.

The sync runs Sundays at 06:00 UTC.

---

## Day-2 operations

The following topics each have their own guide. Read them once your instance
is up and running:

| Task | Guide |
|------|-------|
| Verify backups, test restore, set up offsite copies | [`docs/administration/backups.md`](../administration/backups.md) |
| Monitor logs, set up Prometheus scraping, alerting | [`docs/administration/monitoring-and-logging.md`](../administration/monitoring-and-logging.md) |
| Tune Postgres, JVM heap, disk capacity | [`docs/administration/maintenance.md`](../administration/maintenance.md) |
| Upgrade to a new WealthView version | [`upgrading.md`](upgrading.md) |
| Harden the host OS and container runtime | [`security-hardening.md`](security-hardening.md) |
| Diagnose problems | [`docs/administration/troubleshooting.md`](../administration/troubleshooting.md) |

---

## Quick command reference

```bash
# Start / stop / restart
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml down                    # stop; preserve data
docker compose -f docker-compose.prod.yml restart app             # restart one service

# Status + logs
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml logs --tail=200 db

# Rebuild after a git pull
git pull
docker compose -f docker-compose.prod.yml up --build -d

# Manual backup
docker compose -f docker-compose.prod.yml exec backup /backup.sh

# Restore a specific dump
./infra/backup/restore.sh backups/wealthview_2026-04-22_03-00.dump

# DANGER — deletes the database volume (everything is gone)
docker compose -f docker-compose.prod.yml down -v
```

---

## Related guides

- [Quick Start](quickstart.md) — 5-minute local evaluation.
- [Cloudflared Deployment](cloudflared.md) — step-by-step Cloudflare Tunnel setup.
- [TLS and Nginx](tls-and-nginx.md) — nginx + Let's Encrypt on the host.
- [Security Hardening](security-hardening.md) — firewall, SSH, secrets, app-level security.
- [Upgrading](upgrading.md) — keep your instance up to date.
