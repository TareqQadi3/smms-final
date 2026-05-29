#!/bin/bash
# ══════════════════════════════════════════════════════
#  SMMS Backend – Docker Build & Push
# ══════════════════════════════════════════════════════

set -e

# ── Config ────────────────────────────────────────────
REGISTRY=${REGISTRY:-docker.io}
NAMESPACE=${NAMESPACE:-smms}
VERSION=${VERSION:-1.0.0}
IMAGE_NAME="$REGISTRY/$NAMESPACE/backend"
IMAGE_TAG="$IMAGE_NAME:$VERSION"
IMAGE_LATEST="$IMAGE_NAME:latest"

echo "🔨 Building: $IMAGE_TAG"

# ── Build ─────────────────────────────────────────────
docker build \
  --file backend/Dockerfile \
  --tag "$IMAGE_TAG" \
  --tag "$IMAGE_LATEST" \
  --build-arg BUILDKIT_INLINE_CACHE=1 \
  .

echo "✓ Build complete"

# ── Push (optional) ──────────────────────────────────
if [ "$1" = "push" ]; then
  echo "📤 Pushing to registry..."
  docker push "$IMAGE_TAG"
  docker push "$IMAGE_LATEST"
  echo "✓ Push complete"
fi

# ── Run locally (dev) ────────────────────────────────
if [ "$1" = "run" ]; then
  echo "▶️  Running locally..."
  docker run -it --rm \
    -e SPRING_PROFILES_ACTIVE=dev \
    -e DB_HOST=host.docker.internal \
    -e DB_USER=smms_user \
    -e DB_PASSWORD=changeme \
    -e DB_NAME=smms_db \
    -e JWT_SECRET=$(openssl rand -base64 43) \
    -p 8080:8080 \
    "$IMAGE_TAG"
fi

echo "Done!"
