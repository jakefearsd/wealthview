[← Back to README](../README.md)

# Production Deployment

Guide for deploying WealthView to a VPS or server environment.

## Security Checklist

1. **Set strong secrets** -- never deploy with default values:
   ```bash
   export JWT_SECRET=$(openssl rand -base64 48)
   export DB_PASSWORD=$(openssl rand -base64 24)
   export SUPER_ADMIN_PASSWORD=$(openssl rand -base64 16)
   docker compose up --build -d
   ```

2. **Put a reverse proxy in front** -- the application listens on plain HTTP (port 8080 inside the container, mapped to 80 on the host). Use nginx, Caddy, or Traefik to terminate TLS:
   ```nginx
   # Example nginx config
   server {
       listen 443 ssl;
       server_name finance.example.com;

       ssl_certificate     /etc/ssl/certs/finance.example.com.pem;
       ssl_certificate_key /etc/ssl/private/finance.example.com.key;

       location / {
           proxy_pass http://127.0.0.1:80;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           proxy_set_header X-Forwarded-Proto $scheme;
       }
   }
   ```

3. **Restrict network access** -- bind Docker's published port to localhost if using a reverse proxy on the same host:
   ```yaml
   # docker-compose.override.yml
   services:
     app:
       ports:
         - "127.0.0.1:80:8080"
   ```

4. **Change the super-admin password** after first login or set `SUPER_ADMIN_PASSWORD` to a strong value before first start.

5. **Finnhub API key** -- sign up at [finnhub.io](https://finnhub.io) for a free API key. Without it, the daily price sync job is disabled and you must enter prices manually.

## Backup and Restore

The database volume (`pgdata`) holds all application state. Back it up regularly.

For comprehensive backup operations including automated scheduling, retention, and monitoring, see the [Backup Operations Guide](doing_backups.md).

**Quick dump:**
```bash
docker compose exec db pg_dump -U wv_app wealthview > backup_$(date +%Y%m%d).sql
```

**Restore from dump:**
```bash
docker compose exec -T db psql -U wv_app wealthview < backup_20260307.sql
```

**Volume-level backup** (alternative):
```bash
docker compose down
docker run --rm -v wealthview_pgdata:/data -v $(pwd):/backup alpine \
  tar czf /backup/pgdata_backup.tar.gz -C /data .
docker compose up -d
```

## Health Check

The PostgreSQL container includes a health check (`pg_isready`). The application container depends on it and will not start until the database is ready. To verify the application is running:

```bash
curl -s http://localhost/api/v1/auth/login | head -c 100
# Should return a JSON error (no credentials), not a connection refused
```

## Upgrading

1. Pull the latest code or release.
2. Back up the database (see above).
3. Rebuild and restart:
   ```bash
   docker compose up --build -d
   ```
   Flyway automatically applies any new migrations on startup. Migrations are immutable once released -- you never need to manually run SQL.

## Resource Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| CPU       | 1 core  | 2+ cores    |
| RAM       | 1 GB    | 2+ GB       |
| Disk      | 1 GB    | 10+ GB (grows with price history and import data) |
| Docker    | 20.10+  | Latest stable |

---

## Related Docs

- [Backup Operations Guide](doing_backups.md) — Automated backup scheduling, restore procedures, and troubleshooting
- [Configuration Reference](configuration.md) — Environment variables and Spring profiles
- [Administration Guide](administration.md) — Tenant management, roles, and audit log
