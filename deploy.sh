#!/usr/bin/env bash
set -euo pipefail

# Deploy WealthView to a remote server via SSH.
#
# Usage:
#   DEPLOY_HOST=jake@192.168.1.50 ./deploy.sh
#
# Environment variables:
#   DEPLOY_HOST  (required)  SSH destination, e.g. jake@192.168.1.50
#   DEPLOY_DIR   (optional)  Remote install directory (default: /opt/wealthview)

if [[ -z "${DEPLOY_HOST:-}" ]]; then
    echo "ERROR: DEPLOY_HOST is required (e.g. DEPLOY_HOST=jake@server ./deploy.sh)"
    exit 1
fi

DEPLOY_DIR="${DEPLOY_DIR:-/opt/wealthview}"

# Resolve an explicit version tag for the image instead of pushing :latest,
# which would silently pick up whatever is in the remote cache on the next
# compose pull. Priority: WEALTHVIEW_VERSION env var > git describe > short SHA.
if [[ -n "${WEALTHVIEW_VERSION:-}" ]]; then
    VERSION="$WEALTHVIEW_VERSION"
elif VERSION="$(git describe --tags --always --dirty 2>/dev/null)"; then
    :
else
    VERSION="$(git rev-parse --short HEAD 2>/dev/null || date -u +%Y%m%d%H%M%S)"
fi
# Bare repository name for the locally-built image. Also exported as
# WEALTHVIEW_IMAGE on the remote so compose resolves the tarball we shipped
# rather than the GHCR default — see the comment at that export below.
IMAGE_REPO="wealthview"
IMAGE_NAME="${IMAGE_REPO}:${VERSION}"
TARBALL="/tmp/wealthview-image.tar.gz"

echo "==> Building Docker image ${IMAGE_NAME} locally..."
docker build -t "$IMAGE_NAME" .

echo "==> Saving image to tarball..."
docker save "$IMAGE_NAME" | gzip > "$TARBALL"
echo "    Image saved to $TARBALL ($(du -h "$TARBALL" | cut -f1))"

echo "==> Creating remote directory $DEPLOY_DIR..."
ssh "$DEPLOY_HOST" "sudo mkdir -p $DEPLOY_DIR && sudo chown \$(whoami) $DEPLOY_DIR"

echo "==> Transferring compose files and backup infra..."
scp docker-compose.prod.yml "$DEPLOY_HOST:$DEPLOY_DIR/docker-compose.yml"
scp -r infra/ "$DEPLOY_HOST:$DEPLOY_DIR/infra/"

# Check if .env exists on remote — if not, copy example and abort with instructions
if ! ssh "$DEPLOY_HOST" "test -f $DEPLOY_DIR/.env"; then
    echo "==> No .env found on remote — copying .env.example..."
    scp .env.example "$DEPLOY_HOST:$DEPLOY_DIR/.env"
    echo ""
    echo "============================================================"
    echo "  FIRST DEPLOY: Edit secrets before continuing!"
    echo ""
    echo "  ssh $DEPLOY_HOST"
    echo "  nano $DEPLOY_DIR/.env"
    echo ""
    echo "  At minimum, change DB_PASSWORD, JWT_SECRET, and"
    echo "  SUPER_ADMIN_PASSWORD from their CHANGE_ME defaults."
    echo ""
    echo "  Then re-run this script."
    echo "============================================================"
    exit 0
fi

echo "==> Transferring image tarball..."
scp "$TARBALL" "$DEPLOY_HOST:/tmp/wealthview-image.tar.gz"

echo "==> Loading image and restarting services on remote..."
ssh "$DEPLOY_HOST" bash -s "$DEPLOY_DIR" "$VERSION" "$IMAGE_REPO" <<'REMOTE'
set -euo pipefail
DEPLOY_DIR="$1"
VERSION="$2"
IMAGE_REPO="$3"
echo "  Loading Docker image..."
docker load < /tmp/wealthview-image.tar.gz
rm -f /tmp/wealthview-image.tar.gz
cd "$DEPLOY_DIR"
# The compose file resolves the app image as
# ${WEALTHVIEW_IMAGE:-ghcr.io/jakefearsd/wealthview}:${WEALTHVIEW_VERSION}.
# This script deliberately ships a locally-built tarball instead of pulling
# from a registry, so point WEALTHVIEW_IMAGE at the bare repository name the
# tarball was tagged with — otherwise compose ignores the image we just loaded
# and tries to pull from GHCR. Passed in from IMAGE_REPO above rather than
# repeated here: the heredoc is quoted, so a second literal could drift out of
# sync with the tag the image was actually built under.
export WEALTHVIEW_IMAGE="$IMAGE_REPO"
echo "  Stopping existing services..."
WEALTHVIEW_VERSION="$VERSION" docker compose down
echo "  Starting services (${IMAGE_REPO}:${VERSION})..."
WEALTHVIEW_VERSION="$VERSION" docker compose up -d
echo "  Waiting for health check..."
sleep 5
WEALTHVIEW_VERSION="$VERSION" docker compose ps
REMOTE

rm -f "$TARBALL"
echo "==> Deploy complete!"
