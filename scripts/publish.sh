#!/usr/bin/env bash
#
# Build and publish the Docker image to Docker Hub.
#
# Prereqs:
#   - Docker daemon running
#   - docker login   (as the user that owns the IMAGE repo, e.g. `docker login -u uditgaurav`)
#
# Usage:
#   ./scripts/publish.sh                 # builds + pushes uditgaurav/sample-jmeter-test:{1.0.0,latest}
#   IMAGE=you/your-repo VERSION=2.0.0 ./scripts/publish.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

IMAGE="${IMAGE:-uditgaurav/sample-jmeter-test}"
VERSION="${VERSION:-1.0.0}"

echo "==> Building $IMAGE:$VERSION (and :latest)"
docker build -t "$IMAGE:$VERSION" -t "$IMAGE:latest" .

echo "==> Pushing $IMAGE:$VERSION"
docker push "$IMAGE:$VERSION"
echo "==> Pushing $IMAGE:latest"
docker push "$IMAGE:latest"

echo
echo "Published:"
echo "  docker pull $IMAGE:$VERSION"
echo "  docker pull $IMAGE:latest"
