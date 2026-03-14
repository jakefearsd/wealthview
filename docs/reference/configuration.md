[← Back to README](../../README.md)

# Configuration Reference

All configuration is via environment variables passed through `docker-compose.yml` or set in the shell for development.

## Core Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_PASSWORD` | `wv_dev_pass` | PostgreSQL password (used by both the database and the application) |
| `JWT_SECRET` | `production-secret-key-must-be-at-least-32-characters` | HMAC-SHA256 signing key for JWT tokens. **Must be at least 32 characters.** |
| `SUPER_ADMIN_PASSWORD` | `admin123` | Password for the auto-created `admin@wealthview.local` account |
| `FINNHUB_API_KEY` | *(empty)* | Finnhub API key for live stock prices. When empty, the scheduled price sync is disabled. |
| `ZILLOW_ENABLED` | `false` | Enable Zillow property valuation scraping. When `true`, a weekly job (Sunday 6 AM) scrapes Zestimates for all properties. |

## JWT Token Lifetimes

Configured in `application.yml` (not typically overridden via env vars):

| Setting | Default | Description |
|---------|---------|-------------|
| `app.jwt.access-token-expiration` | `3600000` (1 hour) | Access token lifetime in milliseconds |
| `app.jwt.refresh-token-expiration` | `86400000` (24 hours) | Refresh token lifetime in milliseconds |

## Finnhub Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `app.finnhub.base-url` | `https://finnhub.io` | Finnhub API base URL |
| `app.finnhub.rate-limit-ms` | `1100` | Delay between API calls (free tier: 60 req/min) |

## Zillow Settings

| Setting | Default | Description |
|---------|---------|-------------|
| `app.zillow.enabled` | `false` | Enable/disable Zillow valuation scraping |
| `app.zillow.timeout-ms` | `10000` | HTTP timeout for Zillow requests |
| `app.zillow.rate-limit-ms` | `5000` | Delay between scrape requests |
| `app.zillow.sync-cron` | `0 0 6 * * SUN` | Cron schedule for automatic valuation sync |

## Spring Profiles

| Profile | Activated By | Behavior |
|---------|-------------|----------|
| `dev` | Default when running from IDE/CLI | Debug logging, formatted SQL output, CORS allows `localhost:5173` |
| `docker` | `docker-compose.yml` | INFO logging, CORS allows `localhost`, super-admin auto-created |

---

## Related Docs

- [Deployment Guide](../deployment/production-setup.md) — Production security checklist and resource requirements
- [Development Guide](../development.md) — Local setup and build commands
