[<- Back to README](../../README.md)

# Quick Start Guide

Get WealthView running locally in about 5 minutes. This uses the development
Docker Compose setup, which is ideal for trying the app out. For a real
deployment (public URL, TLS, backups), see
[production-setup.md](production-setup.md).

---

## Prerequisites

- **Docker** with the Compose plugin (`docker compose` as two words — not the
  legacy `docker-compose`).
- **1 GB RAM** minimum (2 GB recommended).
- **Port 80** available on the host.

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

Open `.env` in an editor and replace every `CHANGE_ME` with a random value.
You can generate values directly on the command line:

```bash
openssl rand -base64 24   # DB_PASSWORD
openssl rand -base64 48   # JWT_SECRET (must be 32+ chars)
openssl rand -base64 18   # SUPER_ADMIN_PASSWORD
```

For a local trial you can leave `FINNHUB_API_KEY`, `ZILLOW_ENABLED`,
`CORS_ORIGIN`, and `APP_PORT` at their defaults.

Lock down file permissions:

```bash
chmod 600 .env
```

---

## Step 3: Start WealthView

```bash
docker compose up --build -d
```

Line-by-line:

- `docker compose up` — create and start every service in the default
  `docker-compose.yml` file.
- `--build` — build the application Docker image from source before starting.
  First build takes 2–5 minutes (it pulls Maven + npm dependencies). Later
  builds reuse Docker's layer cache and are much faster.
- `-d` — detach; run in the background.

Watch the startup logs until you see `Started WealthviewApplication`:

```bash
docker compose logs -f app
```

Press `Ctrl-C` to stop tailing. The containers keep running.

---

## Step 4: Verify

Check that both containers are healthy:

```bash
docker compose ps
```

You should see two services (`app` and `db`) both marked `running`. The
`app` row also shows `(healthy)` once the container-level HEALTHCHECK passes
(30–60 seconds after startup).

Hit the health endpoint:

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

A demo tenant is seeded automatically with the `docker` profile. You can
also log in with:

- **Email:** `demo@wealthview.local`
- **Password:** `demo123`

The demo account has sample accounts, holdings, transactions, and a rental
property so you can click around without importing your own data.

---

## What's included

The default Compose setup runs two containers:

| Container | Role |
|-----------|------|
| **db** | PostgreSQL 16 with persistent storage in a named Docker volume (`pgdata`). |
| **app** | Spring Boot application serving both the API and the React frontend at port 80. |

The `docker` Spring profile is active — this enables seeded demo data and
allows `http://localhost` as a valid CORS origin. It is **not** suitable for
internet-facing deployments.

---

## Stopping / restarting

```bash
# Stop everything, preserve data.
docker compose down

# Stop and DELETE the database volume. Everything is gone after this.
docker compose down -v

# Restart after a code change.
docker compose up --build -d

# Tail combined logs.
docker compose logs -f
```

---

## Next steps

- [Production Setup](production-setup.md) — deploy on a real host with TLS,
  backups, and an edge proxy.
- [Cloudflare Tunnel](cloudflared.md) — expose a self-hosted server to the
  internet without opening any ports.
- [TLS and Nginx](tls-and-nginx.md) — host-managed TLS via Let's Encrypt.
- [Security Hardening](security-hardening.md) — lock down your deployment.
- [Upgrading](upgrading.md) — keep your instance current.
