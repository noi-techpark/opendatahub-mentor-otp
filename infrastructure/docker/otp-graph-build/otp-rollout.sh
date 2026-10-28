#!/bin/bash
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# Puts the OTP image the deployment names into service without downtime: docker rollout starts a
# second otp and stops the old one once the new one is healthy. An image with no graph published
# yet, or an OTP that never turns healthy, leaves the running one in place.
#
#     otp-rollout.sh            when the image or its graph changed since the running OTP started
#     otp-rollout.sh --force    a restart regardless
#
# Takes the build lock, unless run-build.sh already holds it.

set -euo pipefail

# The deployment's current release, and the OTP project's name.
: "${OTP_DEPLOY:?}" "${OTP_PROJECT:?}"
FORCE=
[ "${1:-}" = --force ] && FORCE=1

[ -f "$OTP_DEPLOY/docker-compose.otp.yml" ] || { echo "no $OTP_DEPLOY/docker-compose.otp.yml, so no OTP to roll out"; exit 0; }

if [ -z "${BUILD_LOCK_HELD:-}" ]; then
  exec 9>/build/.build.lock
  flock -n 9 || { echo "waiting for the build in progress"; flock 9; }
fi

cd "$OTP_DEPLOY"
COMPOSE=(--project-name "$OTP_PROJECT" --file docker-compose.otp.yml)

# Read with sed: OTP_JAVA_OPTS holds unquoted spaces, which sourcing would split. Must match otp's
# image line in docker-compose.otp.yml.
env_value() { sed -n "s/^$1=//p" .env | tail -1; }
IMAGE="$(env_value DOCKER_IMAGE_OTP):$(env_value DOCKER_TAG)"

# An interrupted rollout leaves more than one otp; keep a healthy one, preferring the current image.
ids=$(docker compose "${COMPOSE[@]}" ps --quiet otp)
if [ "$(echo "$ids" | wc -w)" -gt 1 ]; then
  keep=$(for id in $ids; do
    docker inspect --format '{{.State.Health.Status}} {{.Config.Image}} {{.Id}}' "$id"
  done | awk -v img="$IMAGE" '$1 == "healthy" { print ($2 == img ? 0 : 1), $3 }' | sort | head -1 | cut -d' ' -f2)
  keep=${keep:-$(echo "$ids" | head -1)}
  for id in $ids; do [[ "$keep" == "$id"* ]] || docker rm -f "$id"; done
fi

# --pull never: the deploy pulls on the host, with the registry login this container does not have.
ID=$(docker run --rm --pull never --entrypoint java "$IMAGE" \
  -cp @/app/jib-classpath-file @/app/jib-main-class-file --serializationVersionId)
GRAPH=/graph/graph-$ID.obj
if [ ! -f "$GRAPH" ]; then
  echo "no graph-$ID.obj for $IMAGE yet; the running OTP serves on until the build publishes one"
  exit 0
fi

RUNNING=$(docker compose "${COMPOSE[@]}" ps --quiet otp | head -1)
if [ -n "$RUNNING" ] && [ -z "$FORCE" ]; then
  image=$(docker inspect --format '{{.Config.Image}}' "$RUNNING")
  started=$(date -d "$(docker inspect --format '{{.State.StartedAt}}' "$RUNNING")" +%s)
  if [ "$image" = "$IMAGE" ] && [ "$(stat -c %Y "$GRAPH")" -le "$started" ]; then
    echo "OTP is current: $IMAGE on graph-$ID.obj"
    exit 0
  fi
fi

# The timeout covers loading a full graph; --wait-after-healthy lets Traefik pick up the new
# container before the old one stops.
echo "rolling out $IMAGE on graph-$ID.obj"
docker rollout "${COMPOSE[@]}" --timeout 1800 --wait-after-healthy 10 otp
