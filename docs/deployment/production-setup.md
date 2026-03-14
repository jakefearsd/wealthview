[<- Back to README](../../README.md)

# Production Setup

This guide walks through deploying WealthView on a VPS with TLS, automated backups,
and a reverse proxy. By the end you will have a fully production-ready instance.

## Architecture Overview

The production stack runs five containers:

```
                  +----------+
    Internet ---->|  nginx   |----> app:8080 (Spring Boot)
     :80/:443    +----------+         |
                      |               v
                  +----------+   +----------+
                  | certbot  |   |    db    |
                  +----------+   | (Pg 16) |
                                 +----------+
                                      |
                                 +----------+
                                 |  backup  |
                                 +----------+
```

| Container | Role |
|-----------|------|
| **db** | PostgreSQL 16. Stores all application data. Data persisted in a Docker volume. |
| **app** | Spring Boot application. Serves the API and React frontend. Runs Flyway migrations on startup. Not directly accessible from outside -- nginx proxies to it. |
| **nginx** | Reverse proxy and TLS termination. Serves HTTPS with security headers. Redirects HTTP to HTTPS. |
| **certbot** | Automated Let's Encrypt certificate provisioning and renewal. Runs a renewal check every 12 hours. |
| **backup** | Runs `pg_dump` daily at 3 AM UTC. Cleans up old backups based on retention policy. |

## VPS Requirements

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| OS | Ubuntu 22.04/24.04 or Debian 12 | Ubuntu 24.04 LTS |
| CPU | 1 core | 2+ cores |
| RAM | 1 GB | 2+ GB |
| Disk | 1 GB | 10+ GB |
| Network | Port 80 and 443 open | Static IP |

You also need a **domain name** with a DNS A record pointing to your server's IP address.

## Step 1: Install Docker

Follow the [official Docker installation guide](https://docs.docker.com/engine/install/)
for your distribution. Make sure the Compose plugin is included:

```bash
docker --version
docker compose version
```

Add your user to the `docker` group so you don't need `sudo` for every command:

```bash
sudo usermod -aG docker $USER
# Log out and back in for the group change to take effect
```

## Step 2: Clone the Repository

```bash
git clone https://github.com/your-org/wealthview.git
cd wealthview
```

## Step 3: Create the `.env` File

Create a `.env` file in the project root with all configuration variables:

```bash
cat > .env << 'EOF'
# --- Required ---
DB_PASSWORD=<generated>              # Database password
JWT_SECRET=<generated>               # JWT signing key (32+ chars, HMAC-SHA256)
SUPER_ADMIN_PASSWORD=<generated>     # Super-admin account password

# --- Optional ---
FINNHUB_API_KEY=                     # Finnhub API key for live stock prices
ZILLOW_ENABLED=false                 # Enable Zillow property valuations
BACKUP_RETENTION_DAYS=14             # Days to keep backup files
EOF
```

Generate strong values for each required secret:

```bash
# Database password
openssl rand -base64 24

# JWT signing key (must be 32+ characters for HMAC-SHA256)
openssl rand -base64 48

# Super-admin password
openssl rand -base64 18
```

Copy each generated value into the `.env` file, replacing the `<generated>` placeholders.

Lock down file permissions:

```bash
chmod 600 .env
```

## Step 4: Set Up DNS

Create a DNS A record pointing your domain to the server's IP:

| Type | Name | Value | TTL |
|------|------|-------|-----|
| A | wealthview.example.com | 203.0.113.50 | 300 |

Verify DNS propagation:

```bash
dig +short wealthview.example.com
# Should return your server's IP
```

## Step 5: Provision the Initial TLS Certificate

The nginx container expects TLS certificates to exist before it starts. You need to
provision the first certificate manually using certbot in standalone mode.

Make sure port 80 is open and not in use:

```bash
sudo lsof -i :80
```

Run certbot to obtain the initial certificate:

```bash
docker run --rm -p 80:80 \
  -v $(pwd)/certbot-certs:/etc/letsencrypt \
  certbot/certbot certonly \
  --standalone \
  -d wealthview.example.com \
  --agree-tos \
  --email you@example.com \
  --non-interactive
```

Replace `wealthview.example.com` with your actual domain and `you@example.com` with
your email address.

Verify the certificate was created:

```bash
ls certbot-certs/live/wealthview.example.com/
# Should contain: cert.pem  chain.pem  fullchain.pem  privkey.pem
```

## Step 6: Update Nginx Configuration

Edit `nginx-prod.conf` to set your domain name:

```bash
# Replace the server_name placeholder with your domain
sed -i 's/server_name _;/server_name wealthview.example.com;/' nginx-prod.conf
```

If your domain differs from `wealthview`, also update the SSL certificate paths
in the same file to match the directory name under `/etc/letsencrypt/live/`.

## Step 7: Start the Production Stack

```bash
docker compose -f docker-compose.prod.yml up --build -d
```

This builds the application image, starts all five containers, runs database
migrations, and begins serving traffic.

## Step 8: Verify the Deployment

Check that all containers are running:

```bash
docker compose -f docker-compose.prod.yml ps
```

All five services should show as running or healthy.

Check the health endpoint:

```bash
curl -s https://wealthview.example.com/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

Check application logs for migration and startup status:

```bash
docker compose -f docker-compose.prod.yml logs app | head -50
```

Look for:
- `Successfully applied N migrations` (Flyway)
- `Started WealthviewApplication` (Spring Boot startup complete)

## Step 9: First Login

Open `https://wealthview.example.com` in your browser.

Log in with the super-admin account:

- **Email:** `admin@wealthview.local`
- **Password:** the `SUPER_ADMIN_PASSWORD` value from your `.env` file

The super-admin account is created automatically on first startup.

---

## Service Details

### db (PostgreSQL 16)

- Data stored in the `pgdata` Docker volume
- Health check: `pg_isready -U wv_app -d wealthview`
- Not exposed to the host network (no published ports)
- Connected to the app and backup containers via the internal Docker network

### app (Spring Boot)

- Runs Flyway migrations automatically on startup
- Listens on port 8080 internally (not published to host)
- Uses `expose: 8080` so nginx can reach it within the Docker network
- Health check: `GET /actuator/health`
- Spring profile: `docker` (set via `SPRING_PROFILES_ACTIVE`)

### nginx (Reverse Proxy)

- Publishes ports 80 and 443 to the host
- Port 80: serves Let's Encrypt ACME challenges, redirects all other traffic to HTTPS
- Port 443: TLS termination with security headers, proxies to `app:8080`
- Security headers: HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy
- Configuration file: `nginx-prod.conf`

### certbot (Certificate Renewal)

- Runs in a loop: attempts renewal every 12 hours
- Certificates are valid for 90 days; renewal happens automatically at 60 days
- Uses the webroot challenge method (writes to a shared volume that nginx serves)
- No manual intervention needed after initial provisioning

### backup (Database Backups)

- Runs `pg_dump -Fc` daily at 3 AM UTC
- Backup files stored in `./backups/` on the host
- Filename format: `wealthview_YYYY-MM-DD_HH-MM.dump`
- Automatically deletes backups older than `BACKUP_RETENTION_DAYS` (default: 14)
- Restore script: `./infra/backup/restore.sh`
- Built from `infra/backup/` (Alpine + postgresql16-client + crond)

---

## Resource Monitoring

Monitor container resource usage:

```bash
docker stats --no-stream
```

Check disk usage by Docker:

```bash
docker system df
```

Check backup directory size:

```bash
du -sh backups/
```

Monitor application logs:

```bash
# All containers
docker compose -f docker-compose.prod.yml logs -f

# Specific container
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml logs -f nginx
```

---

## Optional: Finnhub Price Feed

WealthView can fetch live stock prices from [Finnhub](https://finnhub.io/).

1. Sign up for a free account at [finnhub.io](https://finnhub.io/)
2. Copy your API key from the dashboard
3. Add it to your `.env` file:
   ```
   FINNHUB_API_KEY=your_api_key_here
   ```
4. Restart the app container:
   ```bash
   docker compose -f docker-compose.prod.yml up -d app
   ```

The free tier supports up to 60 API calls per minute, which is sufficient for
typical personal use.

## Optional: Zillow Property Valuations

To enable Zillow property valuations:

1. Set `ZILLOW_ENABLED=true` in your `.env` file
2. Restart the app container:
   ```bash
   docker compose -f docker-compose.prod.yml up -d app
   ```
3. In the WealthView UI, configure the Zillow ZPID for each property in the
   property settings page

---

## Related Guides

- [Quick Start](quickstart.md) -- simplified local setup for evaluation
- [TLS and Nginx](tls-and-nginx.md) -- detailed TLS configuration walkthrough
- [Security Hardening](security-hardening.md) -- additional security measures
- [Upgrading](upgrading.md) -- how to update your deployment
