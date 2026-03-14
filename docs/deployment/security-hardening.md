[<- Back to README](../../README.md)

# Security Hardening

This guide covers security best practices for a WealthView production deployment.
Many of these measures are already in place in the default configuration; this
document explains them and adds recommendations for the host operating system.

## Secrets Management

### Generate Strong Secrets

Every secret in the `.env` file should be cryptographically random. Never use
dictionary words, reuse secrets across environments, or commit secrets to git.

```bash
# Database password
openssl rand -base64 24

# JWT signing key (must be 32+ characters for HMAC-SHA256)
openssl rand -base64 48

# Super-admin password
openssl rand -base64 18
```

### Protect the `.env` File

The `.env` file contains all deployment secrets. Restrict access:

```bash
chmod 600 .env
```

This ensures only the file owner can read or write it.

Verify that `.env` is listed in `.gitignore`:

```bash
grep '\.env' .gitignore
```

If it is not listed, add it:

```bash
echo '.env' >> .gitignore
```

Never commit `.env` to version control. If secrets are accidentally committed, rotate
them immediately -- deleting the file from git history is not enough.

### Separate Secrets Per Environment

If you run multiple instances (staging, production), use different secrets for each.
Never share `JWT_SECRET` or `DB_PASSWORD` between environments.

---

## Firewall Configuration (ufw)

Lock down the server to only accept traffic on necessary ports:

```bash
# Set default policies
sudo ufw default deny incoming
sudo ufw default allow outgoing

# Allow SSH
sudo ufw allow 22/tcp

# Allow HTTP (needed for certbot ACME challenges and HTTPS redirect)
sudo ufw allow 80/tcp

# Allow HTTPS
sudo ufw allow 443/tcp

# Enable the firewall
sudo ufw enable

# Verify rules
sudo ufw status verbose
```

If you changed the SSH port (see below), replace `22/tcp` with your custom port.

---

## SSH Hardening

### Use SSH Keys Only

Disable password authentication to prevent brute-force attacks:

```bash
sudo nano /etc/ssh/sshd_config
```

Set these directives:

```
PasswordAuthentication no
PubkeyAuthentication yes
```

Ensure your public key is in `~/.ssh/authorized_keys` before disabling passwords.

### Disable Root Login

```
PermitRootLogin no
```

### Optional: Change the SSH Port

Using a non-standard port reduces automated scanning noise (this is not a substitute
for proper authentication):

```
Port 2222
```

If you change the port, update your firewall rules:

```bash
sudo ufw delete allow 22/tcp
sudo ufw allow 2222/tcp
```

### Apply Changes

```bash
sudo systemctl restart sshd
```

Test SSH access in a new terminal before closing your current session.

---

## Docker Security

### Network Isolation in Production

The production Compose file is configured with security in mind:

- **App container** uses `expose: 8080` (not `ports`). It is accessible only within
  the Docker network -- not from the host or the internet. Nginx is the only entry point.
- **Database container** has no published ports. It is only accessible from the app
  and backup containers within the Docker network.
- **Nginx** is the only container that publishes ports (80 and 443) to the host.

### Resource Limits

Prevent runaway containers from consuming all host resources by adding resource
limits to your Compose file:

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          memory: 1g
          cpus: '1.5'
  db:
    deploy:
      resources:
        limits:
          memory: 512m
          cpus: '0.5'
```

### Keep Docker Updated

Regularly update Docker to get security patches:

```bash
sudo apt update && sudo apt upgrade docker-ce docker-ce-cli containerd.io
```

---

## Database Security

### No External Access

In the production Compose file, the database has no published ports. It is only
reachable from other containers on the internal Docker network. This eliminates
the risk of external brute-force attacks against PostgreSQL.

### Strong Password

Use a cryptographically random password for `DB_PASSWORD` (see above). Avoid short
or guessable passwords.

### Connection Limits

For high-traffic scenarios, consider setting PostgreSQL connection limits in
a custom `postgresql.conf`:

```
max_connections = 100
```

For deployments with many concurrent users, consider adding pgbouncer as a
connection pooler.

---

## Application Security

### JWT Configuration

The `JWT_SECRET` is used for HMAC-SHA256 signing of authentication tokens. It must
be at least 32 characters long. A weak or short secret makes token forgery possible.

```bash
# Generate a strong JWT secret
openssl rand -base64 48
```

### Change the Default Super-Admin Password

The super-admin account (`admin@wealthview.local`) is created automatically on
startup. If `SUPER_ADMIN_PASSWORD` is not set, it defaults to `admin123`. Always
set a strong password in production:

```bash
# Generate and set in .env
openssl rand -base64 18
```

### CORS Configuration

CORS settings are controlled by the Spring profile:

- The `docker` profile restricts origins to localhost
- Review and adjust CORS configuration in the application properties if you need
  to allow additional origins

### Tenant Isolation

WealthView enforces tenant isolation at the data access layer. Every database query
filters by `tenant_id`, which is extracted from the authenticated user's JWT token.
The tenant ID never comes from request parameters or user input.

---

## Security Headers

The nginx configuration (`nginx-prod.conf`) includes security headers on all
HTTPS responses:

| Header | Value | Protection |
|--------|-------|------------|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Forces browsers to use HTTPS for 1 year. Prevents SSL stripping attacks. |
| `X-Content-Type-Options` | `nosniff` | Prevents browsers from MIME-sniffing responses. Mitigates drive-by download attacks. |
| `X-Frame-Options` | `DENY` | Prevents the page from being embedded in iframes. Mitigates clickjacking attacks. |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Limits referrer information sent to external sites. Protects URL-based sensitive data. |

These headers are applied by nginx, so they cover all responses (API and frontend).

---

## Regular Updates

### Rebuild with Latest Base Images

Periodically rebuild your containers to pull in security updates for the base OS
images and system packages:

```bash
docker compose -f docker-compose.prod.yml up --build --pull always -d
```

The `--pull always` flag forces Docker to pull the latest versions of base images
(OpenJDK, PostgreSQL, nginx, Alpine) before building.

### Monitor Dependencies

Keep an eye on security advisories for:
- Spring Boot and Spring Security
- PostgreSQL
- nginx
- Let's Encrypt / certbot
- Node.js and npm packages (frontend)

Run `mvn dependency:tree` and `npm audit` periodically to check for known
vulnerabilities in dependencies.

---

## Audit Trail

WealthView logs user actions in the `audit_log` database table. This provides a
record of who did what and when.

Review the audit log periodically by querying the database or accessing the
audit log view in the application at `/audit-log`.

---

## Security Checklist

Use this checklist to verify your deployment:

- [ ] `.env` file has `chmod 600` permissions
- [ ] `.env` is in `.gitignore`
- [ ] `DB_PASSWORD` is randomly generated (24+ characters)
- [ ] `JWT_SECRET` is randomly generated (32+ characters)
- [ ] `SUPER_ADMIN_PASSWORD` is set (not using default `admin123`)
- [ ] Firewall allows only ports 22, 80, and 443
- [ ] SSH password authentication is disabled
- [ ] SSH root login is disabled
- [ ] Database has no published ports in production Compose
- [ ] App has no published ports in production Compose (nginx proxies)
- [ ] TLS is working (check with `curl -v https://yourdomain.com`)
- [ ] Security headers are present (check with `curl -sI`)
- [ ] Backups are running (check `ls -la backups/`)

---

## Related Guides

- [Production Setup](production-setup.md) -- full deployment walkthrough
- [TLS and Nginx](tls-and-nginx.md) -- detailed TLS configuration
- [Upgrading](upgrading.md) -- keeping your deployment up to date
