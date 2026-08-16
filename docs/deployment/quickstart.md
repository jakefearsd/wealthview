[<- Back to README](../../README.md)

# Quick Start Guide

Get WealthView running locally in about 5 minutes. This uses the development
Docker Compose setup (`docker-compose.yml`), which is ideal for trying the app
out. For a real deployment (public URL, TLS, backups), see
[production-setup.md](production-setup.md).

---

## Prerequisites

- **Docker** with the Compose plugin (`docker compose` as two words — not the
  legacy `docker-compose`).
- **1 GB RAM** minimum (2 GB recommended).
- **Port 80** free on the host (the app) and **port 5433** free (PostgreSQL is
  published on 5433 so it doesn't collide with a native PostgreSQL on 5432).
- `curl` and `openssl` — used by `./wv` for health probes and secret generation.

Verify Docker is installed:

```bash
docker --version
docker compose version
```

If either of those fails, follow the official [Docker install
guide](https://docs.docker.com/engine/install/) for your platform.

> **Permissions note (Linux):** if you see "permission denied while trying to
> connect to the Docker daemon socket," add your user to the `docker` group
> and log out and back in: `sudo usermod -aG docker $USER`.

---

## Step 1: Clone the repository

```bash
git clone https://github.com/<your-org>/wealthview.git
cd wealthview
```

---

## Step 2: Create the `.env` file

`.env` holds the secrets the app needs at runtime. Copy the template:

```bash
cp .env.example .env
```

Open `.env` in an editor and replace every `CHANGE_ME`. Four variables are
**required** — the Compose file uses `${VAR:?...}` syntax and refuses to start
without them:

| Variable | Generate with |
|---|---|
| `DB_PASSWORD` | `openssl rand -base64 24` |
| `JWT_SECRET` (32+ chars) | `openssl rand -base64 48` |
| `SUPER_ADMIN_PASSWORD` | `openssl rand -base64 18` |
| `MFA_ENCRYPTION_KEY` (base64 32 bytes) | `openssl rand -base64 32` |

Everything else in `.env.example` is optional for a local trial. Leave
`FINNHUB_API_KEY` and `ZILLOW_ENABLED` alone, and leave `WEALTHVIEW_VERSION`
commented out — setting it flips the tooling into production mode.

> `CORS_ORIGIN` and `APP_PORT` have **no effect** on the dev stack.
> `docker-compose.yml` hardcodes the port mapping `80:8080`, and the `docker`
> Spring profile hardcodes the allowed origin to `http://localhost`. Both
> variables only matter for `docker-compose.prod.yml`.

Two things to know about the placeholders:

- `ProductionConfigValidator` runs on the `docker` profile as well as `prod`.
  It rejects blank values, JWT secrets under 32 characters, the known dev
  defaults (`admin123`, `demo123`), and anything starting with `LOCAL_DEV_`.
- `./wv` refuses to start the stack while any required variable is still
  literally `CHANGE_ME`.

Lock down file permissions:

```bash
chmod 600 .env
```

---

## Step 3: Start WealthView

`./wv` is the single command surface for the stack. It picks the right compose
file automatically — dev, because `WEALTHVIEW_VERSION` is unset.

```bash
./wv up
```

This validates `.env`, runs `docker compose up --build -d`, then polls
`http://localhost/actuator/health` for up to 120 seconds and reports success or
points you at the logs. The first build takes 2–5 minutes (it pulls Maven and
npm dependencies); later builds reuse Docker's layer cache and are much faster.

Useful flags: `--no-build` (skip the rebuild), `--no-detach` (run in the
foreground), `--no-wait` (skip the health poll).

To watch the startup logs until you see `Started WealthviewApplication`:

```bash
./wv logs app
```

Press `Ctrl-C` to stop tailing. The containers keep running.

> The equivalent raw command is
> `docker compose up --build -d`. `./wv` adds the `.env` validation and the
> health wait, so prefer it.

---

## Step 4: Verify

```bash
./wv status
```

This prints the compose mode, the compose file in use, `docker compose ps`, and
a one-shot health probe. You should see two services (`app` and `db`) both
`running`; the `app` row also shows `(healthy)` once the container-level
HEALTHCHECK passes (30–60 seconds after startup).

You can hit the health endpoint directly too:

```bash
curl -s http://localhost/actuator/health
# {"status":"UP"}
```

---

## Step 5: Log in

Open [http://localhost](http://localhost) in your browser.

Log in with the super-admin account:

- **Email:** `admin@wealthview.local`
- **Password:** the `SUPER_ADMIN_PASSWORD` value you put in `.env`

A demo tenant is seeded automatically by `SampleDataInitializer` on the
`docker` profile. You can also log in with:

- **Email:** `demo@wealthview.local`
- **Password:** `demo123`

The demo account has sample accounts, holdings, transactions, and a rental
property so you can click around without importing your own data.

---

## What's included

The default Compose setup runs two containers:

| Container | Role |
|-----------|------|
| **db** | PostgreSQL 16 (image pinned by digest). Data persists in the named Docker volume `pgdata`. Published on host port **5433**. |
| **app** | Spring Boot application serving both the API and the built React SPA. Container port 8080, published on host port **80**. |

The image is built by the multi-stage `Dockerfile` at the repo root: Node 24
Alpine builds the frontend, `maven:3.9-eclipse-temurin-25` builds the backend,
and the runtime layer is `eclipse-temurin:25-jre-alpine` running as the
non-root user `wv`. All three base images are pinned by digest.

The `docker` Spring profile is active — this enables seeded demo data, disables
the `Secure` cookie flag so plain HTTP works, and allows `http://localhost` as
the CORS origin. It is **not** suitable for internet-facing deployments.

There is no `backup` container in the dev stack; that service only exists in
`docker-compose.prod.yml`.

---

## Talking to the database

PostgreSQL is published on host port 5433, so a local backend run
(`mvn spring-boot:run`) or an IDE run config connects without extra config —
`application.yml` defaults to `jdbc:postgresql://localhost:5433/wealthview`.

For an interactive shell inside the container:

```bash
./wv psql            # psql -U wv_app wealthview
```

---

## Stopping / restarting

```bash
./wv down                   # stop everything, preserve the pgdata volume
./wv down --with-volumes    # DESTROY the database volume (prompts first)
./wv restart                # down, then up (volumes preserved)
./wv up                     # rebuild and restart after a code change
./wv logs                   # tail all services
./wv logs --tail 100 app    # last 100 lines of one service, then follow
```

Run `./wv help` for the full operator man page, or `./wv <subcommand> --help`
for per-subcommand detail.

---

## Next steps

- [Production Setup](production-setup.md) — deploy on a real host with TLS,
  backups, and an edge proxy.
- [Operations Handbook](operations.md) — every `./wv` subcommand, end to end.
- [Cloudflare Tunnel](cloudflared.md) — expose a self-hosted server to the
  internet without opening any ports.
- [TLS and Nginx](tls-and-nginx.md) — host-managed TLS via Let's Encrypt.
- [Security Hardening](security-hardening.md) — lock down your deployment.
- [Upgrading](upgrading.md) — keep your instance current.
