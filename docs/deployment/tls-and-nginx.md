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
1–6. Running `curl http://localhost/actuator/health` on the server should
return `{"status":"UP"}`.

**A note on where nginx runs:** this guide installs nginx directly on the
host (as a native system package), not as a Docker container. Neither
`docker-compose.yml` nor `docker-compose.prod.yml` defines an nginx service —
grep them and you will find no mention of it. Running nginx on the host is
simpler for certificate renewals and keeps the app's Docker environment focused
on WealthView itself.

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

nginx will take ports 80 and 443 on the public interface. The `app` service in
`docker-compose.prod.yml` publishes `${APP_PORT:-80}:8080`, so by default it is
already on port 80. Move it to a non-conflicting loopback port.

Edit `.env`:

```dotenv
APP_PORT=8080
```

`APP_PORT` is only honoured by `docker-compose.prod.yml`. The dev file
`docker-compose.yml` hardcodes `80:8080` and ignores the variable — this guide
assumes you are on the production compose file.

Now edit `docker-compose.prod.yml` so the port mapping binds to loopback only
(not the public interface):

```yaml
services:
  app:
    # ...
    ports:
      - "127.0.0.1:${APP_PORT:-80}:8080"
```

The `127.0.0.1:` prefix is important — it ensures the app is only reachable
from the host itself, never directly from the internet. nginx (running on the
host) can still proxy to it.

Restart the app:

```bash
docker compose -f docker-compose.prod.yml up -d app
```

Tell `wv` where to probe for health, so `./wv up`, `./wv status`, and
`./wv update` don't look on port 80. Either set `APP_PORT` (which `wv` reads
from the env file) — already done above — or, if nginx is terminating in front,
pin the full URL in `wv.conf`:

```
WV_HEALTH_URL=https://wealthview.example.com/actuator/health
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

Note: WealthView's Spring Boot layer (`SecurityConfig`) already emits
`Strict-Transport-Security` (`max-age=31536000; includeSubDomains; preload`),
`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
`Permissions-Policy: geolocation=(), microphone=(), camera=(), payment=()`, and
a `Content-Security-Policy` of
`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`.

Four of the headers above are therefore **duplicates at the nginx layer**, and
act as belt-and-suspenders for any request path that bypasses Spring (e.g.
nginx-served error pages). It is safe to have both. `Referrer-Policy` is the
one the app does **not** set, so nginx is its only source.

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
# Required on the prod profile: empty or non-https:// aborts startup.
CORS_ORIGIN=https://wealthview.example.com

# nginx is the immediate peer — trust its forwarded client IP.
APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1
```

`CORS_ORIGIN` is already listed in the `app` service's `environment:` block, so
it reaches the container as soon as you restart. **`APP_RATE_LIMIT_TRUSTED_PROXIES`
is not.** Compose passes only the variables a service explicitly lists, so
setting it in `.env` alone does nothing. Add it to `docker-compose.prod.yml`:

```yaml
services:
  app:
    environment:
      # ...existing entries...
      APP_RATE_LIMIT_TRUSTED_PROXIES: ${APP_RATE_LIMIT_TRUSTED_PROXIES:-}
```

Spring's relaxed binding maps that env name onto the
`app.rate-limit.trusted-proxies` property that `ClientIpResolver` reads.

Restart the app so Spring picks up the new env vars:

```bash
docker compose -f docker-compose.prod.yml up -d app
```

Without the trusted-proxy setting, login audit and rate limit records log the
proxy's IP instead of the real client IP — because every request technically
arrives from nginx. `ClientIpResolver` only honours `X-Forwarded-For` when the
connecting peer is in the trusted list.

> **Confirm the peer IP rather than assuming `127.0.0.1`.** nginx runs on the
> host and connects to a *published* container port, so Docker NATs the
> connection and the container may see the bridge network's gateway address
> (something in `172.x.x.x`) rather than loopback. The value you need is
> whatever the app actually observes as the remote address. Log in once and
> read the IP recorded against that login in **Admin → Login Activity**; that
> is the address to put in `APP_RATE_LIMIT_TRUSTED_PROXIES`. Set it, restart,
> log in again, and confirm the recorded IP has switched to your real client
> address.

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

## About the two `nginx*.conf` files in the repo

The repo root holds `nginx.conf` and `nginx-prod.conf`. **Neither is
referenced by any compose file or by the `Dockerfile`** — they are reference
configs for running nginx *inside* a container alongside WealthView, and you
can ignore both if you followed this guide.

If you are curious what they actually contain:

**`nginx-prod.conf`** — a two-server-block TLS config:

- Port 80 server: serves `/.well-known/acme-challenge/` from `/var/www/certbot`
  (a webroot-mode certbot layout, not the `--nginx` plugin layout this guide
  uses), and 301-redirects everything else to HTTPS.
- Port 443 server: `ssl_protocols TLSv1.2 TLSv1.3`, HSTS with `preload`,
  `X-Content-Type-Options`, `X-Frame-Options: DENY`, and `Referrer-Policy`.
  Both `/api/` and `/` proxy to `http://app:8080` — the Docker service name, so
  it only resolves from inside the compose network.
- `server_name _` (matches any host) and the certificate paths are
  `/etc/letsencrypt/live/**wealthview**/fullchain.pem` and `privkey.pem` — note
  the literal directory name `wealthview`, not your domain. certbot names that
  directory after the first `-d` argument, so you would need
  `--cert-name wealthview` to make the paths line up.

**`nginx.conf`** — a plain-HTTP config that serves a static SPA build from
`/usr/share/nginx/html` and proxies `/api/` to `http://app:8080`. It predates
the current single-image layout, where the Spring Boot container serves the
built frontend itself from `/app/static`. There is nothing to bind-mount that
directory from in the shipped compose files.

To actually use either, you would add an nginx service in a Compose override,
bind-mount the config, and add a `certbot` container plus a shared certificate
volume. Most home-lab / small-team deployments are better served by
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

Set `APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1` in `.env` **and** add it to the
`app` service's `environment:` block in `docker-compose.prod.yml`, then restart
the app. Setting it in `.env` alone has no effect. See
[Step 8](#step-8-update-wealthviews-env).

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
- [Operations Handbook](operations.md) — the `wv` command surface, including
  `WV_HEALTH_URL` for probing through the proxy.
- [Cloudflared Deployment](cloudflared.md) — alternative: no open ports.
- [Security Hardening](security-hardening.md) — host + app-level security.
