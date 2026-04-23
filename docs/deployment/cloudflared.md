[<- Back to README](../../README.md)

# Self-Hosting WealthView Behind Cloudflare Tunnel

This guide walks through exposing a WealthView instance running on a home
server (or any machine without a public IP) using a **Cloudflare Tunnel**
(also known by its CLI name, `cloudflared`).

Cloudflare Tunnel gives you:

- **HTTPS with no open ports.** Your server never accepts inbound traffic.
  `cloudflared` opens an outbound connection to Cloudflare's edge.
- **TLS terminated by Cloudflare.** No certbot, no Let's Encrypt, no certificate
  renewal to manage.
- **A stable public hostname** (e.g. `https://wealthview.yourdomain.com`)
  pointing at your local box — even if it is behind NAT, CG-NAT, or a
  consumer ISP that blocks port 80 / 443.

It is ideal for a home lab, a Raspberry Pi, a spare laptop, or anywhere the
server does not have a clean public IP.

**Before you start:** make sure WealthView itself is already running (see
[production-setup.md](production-setup.md) Steps 1–5). You should be able to
`curl http://localhost/actuator/health` on the server and get `{"status":"UP"}`.

---

## Prerequisites

1. A domain name you manage through **Cloudflare's free tier** (any domain works
   — you just need to have added it to your Cloudflare account).
2. A Cloudflare account.
3. `docker compose` already running WealthView on the host (see above).
4. Outbound HTTPS (port 443) allowed from the server — almost every network
   already permits this.

No inbound ports are required. If your firewall is denying all inbound traffic,
that is fine — leave it that way.

---

## How Cloudflare Tunnel works (quick mental model)

```
  [End user browser]
          |
          |  HTTPS (Cloudflare TLS cert)
          v
  +-------------------+
  | Cloudflare edge   |
  +-------------------+
          |
          |  Outbound QUIC/HTTPS tunnel initiated by your server
          v
  +-------------------------+
  |  Your home server       |
  |                         |
  |  cloudflared ---------> |---->  http://localhost:80  (WealthView container)
  |                         |
  +-------------------------+
```

You install a `cloudflared` binary (or run it as a Docker container) on your
server. It logs into your Cloudflare account once, creates a named tunnel, and
maintains a persistent outbound connection to Cloudflare. You then configure a
DNS `CNAME` at Cloudflare pointing your chosen hostname at that tunnel.

When a user visits `https://wealthview.yourdomain.com`, Cloudflare terminates
TLS and forwards the request through the tunnel to whatever local address you
configured — in our case, `http://localhost:80`, which is the WealthView app
container.

---

## Step 1: Decide on your public hostname

Pick a hostname under a Cloudflare-managed domain. Examples:

- `wealthview.yourdomain.com`
- `money.home.example.net`

Write it down — you'll use the same value three times below.

For the rest of this guide the placeholder is `wealthview.example.com`.
Replace it everywhere with your real choice.

---

## Step 2: Install cloudflared on the server

You have two choices. Pick **one**.

### Option A — Native package (recommended on Ubuntu / Debian)

Installs `cloudflared` as a systemd service, managed by the OS. Simpler to
monitor, restarts cleanly, doesn't depend on Docker.

```bash
# Add Cloudflare's apt repo + signing key
sudo mkdir -p --mode=0755 /usr/share/keyrings
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg \
  | sudo tee /usr/share/keyrings/cloudflare-main.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/cloudflared.list

sudo apt-get update
sudo apt-get install -y cloudflared

cloudflared --version
# Expected: cloudflared version 2024.x.x (or newer)
```

For other distributions, see the
[official install instructions](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/).

### Option B — Docker container

Runs as part of the same Docker environment as WealthView. Simpler if you
don't want to manage a second package manager.

There is no install step up front — we will add a `cloudflared` service to a
Compose override file in [Step 5](#step-5b-docker-compose-override).

---

## Step 3: Authenticate cloudflared with your Cloudflare account

This only needs to be done once per server.

**Option A (native):**

```bash
cloudflared tunnel login
```

**Option B (Docker):**

```bash
docker run --rm -it -v ~/.cloudflared:/root/.cloudflared \
  cloudflare/cloudflared:latest tunnel login
```

Both commands print a URL. Open it in a browser on any computer and log in to
Cloudflare; you'll be asked which of your domains to authorize. Pick the one
you'll use for WealthView.

The command finishes by writing a certificate to `~/.cloudflared/cert.pem`
(native) or `~/.cloudflared/cert.pem` inside the named volume (Docker). Do
not commit this file — it is an account credential.

---

## Step 4: Create a named tunnel

A "tunnel" is a Cloudflare-side object with a UUID. You create it once; it
persists until you delete it.

**Option A (native):**

```bash
cloudflared tunnel create wealthview
```

**Option B (Docker):**

```bash
docker run --rm -it -v ~/.cloudflared:/root/.cloudflared \
  cloudflare/cloudflared:latest tunnel create wealthview
```

Output looks like:

```
Tunnel credentials written to /home/you/.cloudflared/<UUID>.json.
Created tunnel wealthview with id <UUID>
```

Write down the UUID — you will paste it into the config file in Step 5.

You can list your tunnels any time with:

```bash
cloudflared tunnel list
```

---

## Step 5: Configure ingress

You need to tell cloudflared to forward traffic for your hostname to
WealthView's container port. This is done with a small YAML file.

Decide which sub-step to follow based on your choice in Step 2:

### Step 5a — Native cloudflared

Create `/etc/cloudflared/config.yml`:

```bash
sudo mkdir -p /etc/cloudflared
sudo tee /etc/cloudflared/config.yml > /dev/null <<'EOF'
tunnel: <UUID>
credentials-file: /home/<you>/.cloudflared/<UUID>.json

ingress:
  - hostname: wealthview.example.com
    service: http://localhost:80
  - service: http_status:404
EOF
```

Replace `<UUID>`, `<you>`, and `wealthview.example.com` with your real values.
The second ingress rule is a required catch-all — requests that don't match
the hostname above get a 404 instead of reaching WealthView.

If you set `APP_PORT` to something other than `80` in your WealthView `.env`,
change the `service:` line to match (e.g. `http://localhost:8080`).

### Step 5b — Docker Compose override

Create a new file `docker-compose.cloudflared.yml` next to the main compose
file:

```yaml
services:
  cloudflared:
    image: cloudflare/cloudflared:latest
    restart: unless-stopped
    command: tunnel --no-autoupdate run wealthview
    volumes:
      - ~/.cloudflared:/home/nonroot/.cloudflared:ro
    depends_on:
      app:
        condition: service_healthy
    network_mode: "service:app"
```

`network_mode: "service:app"` puts the cloudflared container in the same
network namespace as the `app` container, so `http://localhost:8080` inside
cloudflared reaches the Spring Boot listener directly.

Create `~/.cloudflared/config.yml`:

```yaml
tunnel: <UUID>
credentials-file: /home/nonroot/.cloudflared/<UUID>.json

ingress:
  - hostname: wealthview.example.com
    service: http://localhost:8080
  - service: http_status:404
```

Note the port here is `8080` (the container-internal port, reachable via the
shared network namespace), not `80` / `APP_PORT`.

---

## Step 6: Route DNS to the tunnel

Tell Cloudflare: "incoming requests to `wealthview.example.com` should enter
the `wealthview` tunnel."

**Option A (native):**

```bash
cloudflared tunnel route dns wealthview wealthview.example.com
```

**Option B (Docker):**

```bash
docker run --rm -it -v ~/.cloudflared:/root/.cloudflared \
  cloudflare/cloudflared:latest tunnel route dns wealthview wealthview.example.com
```

This creates a `CNAME` record in your Cloudflare DNS pointing
`wealthview.example.com` at `<UUID>.cfargotunnel.com`. DNS propagation inside
Cloudflare is usually immediate.

---

## Step 7: Update WealthView's `.env`

The app enforces CORS and rate-limit rules based on which public URL is in
use. Edit `.env` at the repo root:

```dotenv
# Your public Cloudflare Tunnel hostname
CORS_ORIGIN=https://wealthview.example.com

# When the app is reached via cloudflared + localhost, the immediate peer
# is the loopback interface. Trust it so the real client IP lands in rate
# limiter and login_activity records.
APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1
```

Restart the app for the change to take effect:

```bash
docker compose -f docker-compose.prod.yml up -d app
```

Without `CORS_ORIGIN` set, the production profile aborts on startup. Without
`APP_RATE_LIMIT_TRUSTED_PROXIES`, every login audit entry will show the
loopback IP instead of the end user's IP.

---

## Step 8: Start cloudflared

**Option A (native as systemd service):**

```bash
sudo cloudflared service install
sudo systemctl start cloudflared
sudo systemctl enable cloudflared
sudo systemctl status cloudflared
```

The `service install` step reads `/etc/cloudflared/config.yml`, installs a
`cloudflared.service` unit, and runs it as the unprivileged `cloudflared`
user.

**Option B (Docker):**

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.cloudflared.yml up -d
```

Check the logs in either case:

```bash
# Native
sudo journalctl -u cloudflared -f

# Docker
docker compose -f docker-compose.prod.yml -f docker-compose.cloudflared.yml logs -f cloudflared
```

You want to see `Connection registered connIndex=0` (and probably
`connIndex=1..3` — cloudflared maintains four redundant connections by
default). Press `Ctrl-C` to stop tailing.

---

## Step 9: Test end-to-end

From any network (not just the one the server is on):

```bash
# 1. DNS resolves to Cloudflare
dig +short wealthview.example.com
# Returns Cloudflare IPs (e.g. 104.21.x.x, 172.67.x.x)

# 2. Health check responds over HTTPS
curl -s https://wealthview.example.com/actuator/health
# {"status":"UP"}

# 3. Security headers are in place
curl -sI https://wealthview.example.com/ | grep -Ei "strict-transport|permissions-policy|x-frame-options|x-content-type-options"
# Expected:
#   strict-transport-security: ...
#   permissions-policy: geolocation=(), microphone=(), camera=(), payment=()
#   x-frame-options: DENY
#   x-content-type-options: nosniff
```

Load `https://wealthview.example.com/` in a browser. The padlock should show
a Cloudflare-issued cert; click through to `/login` and sign in with
`admin@wealthview.local` + your `SUPER_ADMIN_PASSWORD`.

---

## Security notes specific to Cloudflare Tunnel

- **Close inbound ports.** Once the tunnel is working, you should be able to
  drop any inbound firewall rules for HTTP/HTTPS. The only inbound connection
  your server needs is SSH (if at all).
  ```bash
  sudo ufw delete allow 80/tcp    # if previously opened
  sudo ufw delete allow 443/tcp   # if previously opened
  sudo ufw status
  ```
- **Lock the container port to loopback.** If you set `APP_PORT=80` and your
  server also has a public IP, the container is still reachable directly on
  port 80, bypassing Cloudflare. Either:
  - Keep inbound port 80 closed at the firewall (easiest), OR
  - Change `APP_PORT=8080` and bind to loopback only by editing the `ports:`
    line in `docker-compose.prod.yml`:
    ```yaml
    ports:
      - "127.0.0.1:${APP_PORT:-8080}:8080"
    ```
- **Cloudflare Access (optional).** You can require a Cloudflare Access login
  (email OTP, Google SSO, etc.) in addition to WealthView's own authentication.
  In the Cloudflare dashboard: **Zero Trust → Access → Applications → Add**,
  self-hosted, pointed at `wealthview.example.com`.

---

## Troubleshooting

### `curl https://wealthview.example.com` returns 502 Bad Gateway

The tunnel is up but can't reach the app.

```bash
# On the server — is the container responding?
curl -s http://localhost/actuator/health

# If not, inspect WealthView app logs:
docker compose -f docker-compose.prod.yml logs --tail=50 app
```

If the local curl works but the public one doesn't, the ingress rule in
`config.yml` is pointing at the wrong port. Check it matches whatever
`APP_PORT` you configured.

### `dig` returns NXDOMAIN for the hostname

The DNS route was not created. Re-run:

```bash
cloudflared tunnel route dns wealthview wealthview.example.com
```

…and verify the CNAME appears in the Cloudflare dashboard under
**DNS → Records**.

### Login audit / rate limit rows all show 127.0.0.1

Set `APP_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1` in `.env` and restart the app.
See [Step 7](#step-7-update-wealthviews-env).

### cloudflared service won't start — "credentials file missing"

The native systemd service runs as the `cloudflared` user, but the
credentials file was written into your home directory during `tunnel create`.
Copy it into `/etc/cloudflared/` with appropriate ownership:

```bash
sudo cp ~/.cloudflared/<UUID>.json /etc/cloudflared/
sudo chown cloudflared:cloudflared /etc/cloudflared/<UUID>.json
sudo chmod 600 /etc/cloudflared/<UUID>.json
```

Then update `credentials-file:` in `/etc/cloudflared/config.yml` to point at
the new path, and `sudo systemctl restart cloudflared`.

### The app is reachable publicly without HTTPS

This means inbound port 80 on your host is open to the internet and the app
container is listening on it. Close it at the firewall (`sudo ufw deny
80/tcp`) or bind the container port to loopback only (see the
[security notes](#security-notes-specific-to-cloudflare-tunnel) above).

---

## Removing the tunnel

To undo everything:

```bash
# Stop the tunnel
sudo systemctl stop cloudflared            # native
# OR
docker compose -f docker-compose.cloudflared.yml down   # Docker

# Remove the DNS CNAME in the Cloudflare dashboard (DNS → Records)

# Delete the tunnel on the Cloudflare side
cloudflared tunnel delete wealthview
```

---

## Related guides

- [Production Setup](production-setup.md) — the main deployment walkthrough.
- [TLS and Nginx](tls-and-nginx.md) — alternative: host-managed TLS with
  Let's Encrypt.
- [Security Hardening](security-hardening.md) — firewall, SSH, secrets.
