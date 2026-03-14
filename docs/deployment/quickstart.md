[<- Back to README](../../README.md)

# Quick Start Guide

Get WealthView running locally in 5 minutes. This uses the development Docker Compose
setup, which is ideal for trying the app out. For production deployment, see
[production-setup.md](production-setup.md).

## Prerequisites

- **Docker** with the Compose plugin (`docker compose` -- not the legacy `docker-compose`)
- **1 GB RAM** minimum (2 GB recommended)
- **Port 80** available on the host

Verify Docker is installed:

```bash
docker --version
docker compose version
```

## Step 1: Clone the Repository

```bash
git clone https://github.com/your-org/wealthview.git
cd wealthview
```

## Step 2: Create the `.env` File

Create a `.env` file in the project root with three secrets:

```bash
cat > .env << 'EOF'
DB_PASSWORD=<generated>
JWT_SECRET=<generated>
SUPER_ADMIN_PASSWORD=<generated>
EOF
```

Generate strong values for each:

```bash
# Database password
openssl rand -base64 24

# JWT signing key (must be 32+ characters)
openssl rand -base64 48

# Super-admin password
openssl rand -base64 18
```

Copy each output into the corresponding `.env` line.

## Step 3: Start WealthView

```bash
docker compose up --build -d
```

This builds the application image (frontend + backend), starts PostgreSQL, runs
database migrations, and seeds demo data. First build takes 2-5 minutes.

## Step 4: Verify

Check that the health endpoint responds:

```bash
curl http://localhost/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

Check container status:

```bash
docker compose ps
```

Both `app` and `db` should show as healthy/running.

## Step 5: Log In

Open [http://localhost](http://localhost) in your browser.

Log in with the super-admin account:

- **Email:** `admin@wealthview.local`
- **Password:** the `SUPER_ADMIN_PASSWORD` value you set in `.env`

A demo account is also available (seeded automatically with the `docker` profile):

- **Email:** `demo@wealthview.local`
- **Password:** `demo123`

## What's Included

The development Compose setup runs two containers:

- **db** -- PostgreSQL 16 with persistent storage
- **app** -- Spring Boot application serving both the API and the React frontend

This is suitable for local evaluation and development. It does **not** include TLS,
automated backups, or a reverse proxy.

## Next Steps

- [Production Setup](production-setup.md) -- deploy on a VPS with TLS, backups, and nginx
- [TLS and Nginx](tls-and-nginx.md) -- understand the HTTPS configuration
- [Security Hardening](security-hardening.md) -- lock down your deployment
- [Upgrading](upgrading.md) -- keep your instance up to date
