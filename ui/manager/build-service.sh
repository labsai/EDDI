#!/bin/bash
set -e

echo "Building docker image ${DOCKER_IMAGE} version ${VERSION}"
docker build --file ./Dockerfile --tag "${DOCKER_IMAGE_URL}" --tag "${DOCKER_IMAGE_LATEST_URL}" .
