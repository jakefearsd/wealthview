[<- Back to README](../../README.md)

# TLS with Nginx and Let's Encrypt

This guide walks through putting nginx (with an automatically-renewing Let's
Encrypt certificate) in front of the WealthView app container. It is written
for someone who has run `docker compose up` before but has never configured
nginx or certbot.

**Use this guide when:** you have a public IPv4 address and you want to
manage TLS yourself, without depending on Cloudflare or another CDN.

**Use [cloudflared.md](cloudflared.md) instead when:** your server is behind
NAT / CG-NAT, you can't forward ports 80/443, or you prefer to let Cloudflare
handle certificates.

Before you start, complete [production-setup.md](production-setup.md) Steps
1–5. Running `curl http://localhost/actuator/health` on the server should
return `{"status":"UP"}`.

**A note on where nginx runs:** this guide installs nginx directly on the
host (as a native system package), not as a Docker container. Some older
versions of our docs implied nginx lived in `docker-compose.prod.yml` — it
does not. Running nginx on the host is simpler for certificate renewals and
keeps the app's Docker environment focused on WealthView itself.

---

## Prerequisites

- A domain name (e.g. `wealthview.example.com`) whose DNS you control.
- A public IPv4 address on your server.
- Inbound TCP ports 80 and 443 reachable from the internet (Let's Encrypt's
  HTTP-01 challenge needs port 80).
- WealthView already running — the container should be answering on
  `http://localhost:${APP_PORT}` (default `APP_PORT=80`).

---

## Step 1: Change the app's port to not conflict with nginx

nginx will take port 80 and 443 on the public interface. WealthView's `app`
container currently also publishes `80:8080`. Move the container to a
non-conflicting loopback port.

Edit `.env`:

```dotenv
APP_PORT=8080
```

Edit `docker-compose.prod.yml` so the port mapping binds to loopback only
(not the public interface):

```yaml
services:
  app:
    # ...
    ports:
      - "127.0.0.1:${APP_PORT:-8080}:8080"
```

The `127.0.0.1:` prefix is important — it ensures the app is only reachable
from the host itself, never directly from the internet. nginx (running on the
host) can still proxy to it.

Restart the app:

```bash
docker compose -f docker-compose.prod.yml up -d app
```

Verify from the host:

```bash
curl -s http://127.0.0.1:8080/actuator/health
# {"status":"UP"}
```

From anywhere else on the internet, `http://<server-ip>:8080` should now
**fail** to connect. That is correct.

---

## Step 2: Point DNS at the server

Create a DNS `A` record for your domain pointing at the server's public IPv4:

| Type | Name | Value | TTL |
|------|------|-------|-----|
| A | wealthview | 203.0.113.50 | 300 |

Confirm propagation:

```bash
dig +short wealthview.example.com
# Should print your server's public IP.
```

If you get nothing, wait (usually minutes for TTL-300 records) and try again.

---

## Step 3: Open ports 80 and 443 in the firewall

If you're using `ufw`:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw status verbose
```

If you're on a cloud VPS, you may also need to open these ports in the
provider's security group / network ACL.

---

## Step 4: Install nginx and certbot on the host

On Debian / Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y nginx certbot python3-certbot-nginx
```

`python3-certbot-nginx` is the certbot plugin that edits your nginx config
for you — it makes the initial cert provisioning a single command.

Verify:

```bash
sudo nginx -v           # nginx version: nginx/1.2x
certbot --version       # certbot 2.x.x
systemctl status nginx  # active (running)
```

---

## Step 5: Write the initial nginx server block

Create `/etc/nginx/sites-available/wealthview`:

```bash
sudo tee /etc/nginx/sites-available/wealthview > /dev/null <<'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name wealthview.example.com;

    # Temporary — certbot will rewrite this block to also listen on 443
    # and add the ssl_certificate directives.
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Upload size: CSV/OFX imports can be a few MB.
        client_max_body_size 10m;

        # Long-running requests (projection recompute can take ~30s).
        proxy_read_timeout 60s;
    }
}
EOF
```

Replace `wealthview.example.com` with your real domain. Match the port in
`proxy_pass` to the `APP_PORT` you set in `.env` (Step 1).

Enable the site:

```bash
sudo ln -sf /etc/nginx/sites-available/wealthview /etc/nginx/sites-enabled/wealthview

# Disable the default "welcome to nginx" site so it doesn't shadow ours.
sudo rm -f /etc/nginx/sites-enabled/default

# Validate the config before reloading.
sudo nginx -t

# Reload — zero downtime.
sudo systemctl reload nginx
```

Test:

```bash
curl -s http://wealthview.example.com/actuator/health
# {"status":"UP"}
```

HTTP (not HTTPS) works now. HTTPS is next.

---

## Step 6: Obtain the initial TLS certificate

certbot talks to Let's Encrypt, proves you control the domain (HTTP-01
challenge), writes `/etc/letsencrypt/live/wealthview.example.com/fullchain.pem`
+ `privkey.pem`, and edits your nginx config to use them.

```bash
sudo certbot --nginx \
  -d wealthview.example.com \
  --agree-tos \
  --email you@example.com \
  --redirect \
  --non-interactive
```

Flag-by-flag:

- `--nginx` — use the nginx plugin (automatically edit your nginx site file).
- `-d wealthview.example.com` — the domain to certify.
- `--agree-tos` — accept the Let's Encrypt subscriber agreement.
- `--email you@example.com` — used for renewal failure notifications.
- `--redirect` — add a 301 redirect from HTTP → HTTPS.
- `--non-interactive` — don't prompt; fail with a clear error if something's
  missing.

On success, certbot prints:

```
Successfully received certificate.
Certificate is saved at: /etc/letsencrypt/live/wealthview.example.com/fullchain.pem
Key is saved at:         /etc/letsencrypt/live/wealthview.example.com/privkey.pem
```

Test HTTPS:

```bash
curl -s https://wealthview.example.com/actuator/health
# {"status":"UP"}

curl -sI http://wealthview.example.com/
# Should show: HTTP/1.1 301 Moved Permanently, Location: https://...
```

---

## Step 7: Add HTTPS-only security headers

certbot's default config is minimal. Open the now-modified file and add
security headers to the HTTPS server block:

```bash
sudo nano /etc/nginx/sites-enabled/wealthview
```

Inside the `server { listen 443 ssl; ... }` block, add:

```nginx
    # Force HTTPS for one year. Browsers that see this will refuse plain
    # HTTP even if someone types http:// explicitly.
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # Prevent the browser from MIME-sniffing responses.
    add_header X-Content-Type-Options "nosniff" always;

    # The application already sets X-Frame-Options: DENY and
    # Content-Security-Policy: frame-ancestors 'none', so the frame block
    # is defence in depth.
    add_header X-Frame-Options "DENY" always;

    # Trim referrer to just the origin for cross-site requests.
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

Note: WealthView's Spring Boot layer already emits
`Content-Security-Policy`, `Permissions-Policy`, and `X-Content-Type-Options`
headers on every response. The headers above are **duplicates at the nginx
layer** and act as belt-and-suspenders for any request path that bypasses
Spring (e.g. nginx-served error pages). It is safe to have both.

Reload:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Verify the full security header set is present:

```bash
curl -sI https://wealthview.example.com/ | grep -Ei \
  "strict-transport|x-content-type|x-frame|referrer-policy|content-security|permissions-policy"
```

You should see all six.

---

## Step 8: Update WealthView's `.env`

```dotenv
# The public HTTPS URL is now the allowed origin for /api/* requests.
CORS_ORIGIN=https://wealthview.example.com

# nginx is the immediate peer — trust its forwarded client IP.
APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1
```

Restart the app so Spring picks up the new env vars:

```bash
docker compose -f docker-compose.prod.yml up -d app
```

Without `APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1`, login audit and rate
limit records will log the loopback IP instead of the real client IP
(because every request technically arrives from nginx, which runs on
localhost).

---

## Step 9: Confirm automatic certificate renewal

The `certbot` package installs a systemd timer that renews certificates
automatically (Let's Encrypt certs expire every 90 days; certbot renews them
at around 60 days).

```bash
systemctl list-timers | grep certbot
# Expected: certbot.timer  <next run time>  ...

# Dry-run a renewal (does not actually call Let's Encrypt, but validates
# that renewal would work).
sudo certbot renew --dry-run
# Expected output includes: "Congratulations, all simulated renewals succeeded"
```

No further action needed. The timer fires twice daily, notices when a cert
is within 30 days of expiry, renews it, and reloads nginx.

---

## Complete final nginx config (reference)

After all the steps above, your `/etc/nginx/sites-enabled/wealthview` should
look something like this:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name wealthview.example.com;

    # Certbot's HTTP-01 challenge files go here; everything else redirects.
    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name wealthview.example.com;

    ssl_certificate     /etc/letsencrypt/live/wealthview.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/wealthview.example.com/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        client_max_body_size 10m;
        proxy_read_timeout 60s;
    }
}
```

(`options-ssl-nginx.conf` and `ssl-dhparams.pem` are dropped in automatically
by `python3-certbot-nginx` the first time it runs.)

---

## About `nginx-prod.conf` in the repo

The repo contains a file called `nginx-prod.conf` at its root. This is a
reference config for people who prefer to run nginx **inside** a Docker
container alongside WealthView (rather than on the host). It is not wired
into `docker-compose.prod.yml` by default and you can ignore it if you
followed this guide.

If you do want to run nginx in Docker, copy `nginx-prod.conf` into a bind
mount and add a service in a Compose override. The certificate lifecycle
is tricker in that setup (you need a `certbot` container too and a shared
volume). Most home-lab / small-team deployments are better served by
nginx-on-host as described above or by [Cloudflare Tunnel](cloudflared.md).

---

## Troubleshooting

### certbot: "The server was busy in responding to challenge requests"

Usually a sign that another process has port 80. Stop anything using it
(Apache? old nginx? containerized proxy?) before running certbot.

```bash
sudo ss -tlnp | grep :80
```

### Mixed-content warnings in the browser

All static assets come from the same origin, so this shouldn't happen — but
if it does, check that `CORS_ORIGIN` in `.env` starts with `https://`, not
`http://`, and that you restarted the app after editing.

### HTTPS returns 502 Bad Gateway

nginx is running but can't reach the app.

```bash
# Is the app container up?
docker compose -f docker-compose.prod.yml ps

# Does the app answer directly on the host?
curl -s http://127.0.0.1:8080/actuator/health

# nginx error log
sudo tail -50 /var/log/nginx/error.log
```

Common cause: `proxy_pass` in the nginx config points at a different port
than the one in the `app` service's `ports:` mapping. They must match.

### Login audit rows show `127.0.0.1` instead of the real user IP

Set `APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1` in `.env` and restart the
app. See [Step 8](#step-8-update-wealthviews-env).

### Certificate renewal fails

```bash
sudo certbot renew --dry-run
sudo journalctl -u certbot.timer --since "7 days ago"
```

Most common causes:
- Port 80 is no longer reachable from the public internet (firewall change).
- DNS A record was removed or changed.
- nginx is stopped.

Fix the underlying issue, then trigger a renewal manually:

```bash
sudo certbot renew
sudo systemctl reload nginx
```

---

## Related guides

- [Production Setup](production-setup.md) — overall deployment walkthrough.
- [Cloudflared Deployment](cloudflared.md) — alternative: no open ports.
- [Security Hardening](security-hardening.md) — host + app-level security.
