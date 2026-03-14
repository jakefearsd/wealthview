[<- Back to README](../../README.md)

# TLS and Nginx Configuration

This guide explains how the nginx reverse proxy and certbot automatic certificate
renewal work in the WealthView production stack, and how to set up TLS from scratch.

## Prerequisites

- A domain name (e.g., `wealthview.example.com`)
- A DNS A record pointing that domain to your server's IP address
- Ports 80 and 443 open on the server (firewall and cloud provider)

## How It Works

The production Compose file runs two containers that work together for TLS:

1. **nginx** -- reverse proxy that terminates TLS and forwards requests to the app
2. **certbot** -- obtains and renews Let's Encrypt certificates automatically

They share two volumes:
- `certbot-webroot` -- certbot writes ACME challenge files here; nginx serves them
- `certbot-certs` -- certbot stores certificates here; nginx reads them

---

## Nginx Configuration Walkthrough

The configuration file is `nginx-prod.conf`. Here is what each section does.

### Port 80 -- HTTP Server

```nginx
server {
    listen 80;
    server_name _;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}
```

This server handles two things:

- **ACME challenges:** Certbot places challenge files in `/var/www/certbot/.well-known/acme-challenge/`. Nginx serves them so Let's Encrypt can verify domain ownership.
- **HTTPS redirect:** All other HTTP requests get a 301 redirect to the HTTPS version of the same URL.

### Port 443 -- HTTPS Server

```nginx
server {
    listen 443 ssl;
    server_name _;

    ssl_certificate     /etc/letsencrypt/live/wealthview/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/wealthview/privkey.pem;
```

The SSL certificate and private key are loaded from the certbot-certs volume. Update
the path if your domain directory name differs from `wealthview`.

### TLS Protocol and Cipher Configuration

```nginx
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
```

- **TLS 1.2 and 1.3 only** -- older protocols (SSLv3, TLS 1.0, TLS 1.1) are disabled
- **Session cache** -- 10 MB shared cache reduces TLS handshake overhead for returning clients
- **Session timeout** -- cached sessions expire after 10 minutes

### Security Headers

```nginx
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

| Header | Value | Purpose |
|--------|-------|---------|
| Strict-Transport-Security | max-age=31536000 (1 year) | Browsers remember to always use HTTPS |
| X-Content-Type-Options | nosniff | Prevents MIME type sniffing attacks |
| X-Frame-Options | DENY | Prevents clickjacking by blocking iframe embedding |
| Referrer-Policy | strict-origin-when-cross-origin | Limits referrer information sent to other origins |

### Proxy Locations

```nginx
    location /api/ {
        proxy_pass http://app:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        proxy_pass http://app:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
```

Both `/api/` and `/` are proxied to the Spring Boot application at `app:8080`. The
`app` hostname resolves within the Docker network. The forwarding headers ensure the
application knows the original client IP and protocol.

---

## Initial Certificate Provisioning

The nginx HTTPS server requires certificates to exist before it can start. You need
to provision the first certificate before launching the full production stack.

### Step 1: Verify DNS Propagation

```bash
dig +short wealthview.example.com
```

This must return your server's IP address. If it returns nothing or the wrong IP,
wait for DNS propagation (can take up to 48 hours, usually minutes).

### Step 2: Ensure Port 80 Is Open

```bash
# Check if anything is listening on port 80
sudo lsof -i :80

# If using ufw, ensure port 80 is allowed
sudo ufw allow 80/tcp
```

### Step 3: Run Certbot Standalone

With no other service using port 80, run certbot in standalone mode:

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

Replace `wealthview.example.com` and `you@example.com` with your actual values.

### Step 4: Verify the Certificate

```bash
ls certbot-certs/live/wealthview.example.com/
```

You should see: `cert.pem`, `chain.pem`, `fullchain.pem`, `privkey.pem`.

### Step 5: Update nginx-prod.conf

Make sure the certificate path in `nginx-prod.conf` matches the directory name
under `/etc/letsencrypt/live/`:

```nginx
ssl_certificate     /etc/letsencrypt/live/wealthview.example.com/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/wealthview.example.com/privkey.pem;
```

### Step 6: Start the Production Stack

```bash
docker compose -f docker-compose.prod.yml up --build -d
```

---

## Certificate Renewal

Once the production stack is running, certificate renewal is fully automatic.

The certbot container runs in a loop:
1. Sleeps for 12 hours
2. Runs `certbot renew`
3. Repeats

Let's Encrypt certificates are valid for 90 days. Certbot renews them when they
have 30 days or less remaining (around day 60). No manual intervention is needed.

You can verify the renewal process is working:

```bash
docker compose -f docker-compose.prod.yml logs certbot
```

To manually trigger a renewal check:

```bash
docker compose -f docker-compose.prod.yml exec certbot certbot renew --dry-run
```

---

## Custom Domain Configuration

To use your own domain:

1. Update the `server_name` directive in `nginx-prod.conf`:
   ```nginx
   server_name wealthview.example.com;
   ```
   Change this in both the port 80 and port 443 server blocks (or leave as `_` to
   accept any hostname).

2. Update the SSL certificate paths to match your certbot directory name.

3. Restart nginx:
   ```bash
   docker compose -f docker-compose.prod.yml restart nginx
   ```

---

## Testing TLS

Verify that TLS is working correctly:

```bash
curl -v https://wealthview.example.com/actuator/health 2>&1 | head -30
```

Look for:
- `SSL connection using TLSv1.3`
- `subject: CN=wealthview.example.com`
- `issuer: C=US; O=Let's Encrypt`

Check security headers:

```bash
curl -sI https://wealthview.example.com | grep -E "(Strict-Transport|X-Content|X-Frame|Referrer)"
```

Expected output:

```
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
```

---

## Troubleshooting

### DNS Not Propagated

**Symptom:** `dig +short wealthview.example.com` returns nothing or the wrong IP.

**Fix:** Wait for propagation. Check with multiple DNS resolvers:
```bash
dig @8.8.8.8 +short wealthview.example.com
dig @1.1.1.1 +short wealthview.example.com
```

### Port 80 Blocked by Firewall

**Symptom:** Certbot standalone fails with "Could not bind TCP port 80."

**Fix:** Open the port in your firewall and cloud provider's security group:
```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

### Certificate Expired

**Symptom:** Browser shows certificate error; `curl` reports expired cert.

**Fix:** Run a manual renewal:
```bash
docker compose -f docker-compose.prod.yml exec certbot certbot renew --force-renewal
docker compose -f docker-compose.prod.yml restart nginx
```

### Nginx Won't Start -- Certificate Not Found

**Symptom:** Nginx exits immediately with "cannot load certificate" error.

**Fix:** The initial certificate has not been provisioned. Follow the "Initial
Certificate Provisioning" steps above before starting the full stack.

### Mixed Content Warnings in Browser

**Symptom:** Browser console shows "Mixed Content" errors; some resources loaded via HTTP.

**Fix:** Ensure all API calls in the frontend use relative URLs (e.g., `/api/accounts`
instead of `http://server/api/accounts`). The React app is configured to do this
by default.

---

## Related Guides

- [Production Setup](production-setup.md) -- full deployment walkthrough
- [Security Hardening](security-hardening.md) -- additional security measures
