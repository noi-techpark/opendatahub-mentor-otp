#!/bin/bash
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# What cron runs: the configs OTP reads, the build, and the handover of the graph to serving OTP.

set -e
set -o pipefail

DATE="$(date +%Y%m%d_%H%M%S)"

WORK=/build
PUBLISHED=/graph
LOGDIR="$WORK/log"
mkdir -p "$LOGDIR" "$WORK/graph"

# One build or rollout at a time: a rollout runs two serving heaps for a while.
exec 9>"$WORK/.build.lock"
flock -n 9 || { echo "waiting for the build or rollout in progress"; flock 9; }
export BUILD_LOCK_HELD=1

# --pull never: the deploy pulls the serving image on the host, with the registry login this container
# does not have.
ID=$(docker run --rm --pull never --entrypoint java "$OTP_IMAGE" \
  -cp @/app/jib-classpath-file @/app/jib-main-class-file --serializationVersionId)
export OTP_SERIALIZATION_ID=$ID
echo "OTP $OTP_IMAGE writes serialization version $ID"

# Before the build too: an image whose graph already exists goes live without waiting for one.
rollout() { /app/otp-rollout.sh || echo "rollout failed; the running OTP stays"; }
rollout
trap rollout EXIT

# Retain only the last 14 days of logs
find "$LOGDIR" -type f -mtime +14 -delete

# The configs OTP reads, into its base directory. install -C leaves a file whose contents already
# match untouched, timestamp included: build-config.json is an ordinary prerequisite of
# streetGraph-<id>.obj, the other two of graph-<id>.obj, so a fresh timestamp forces that rebuild.
for f in build-config.json otp-config.json router-config.json; do
  install -C -m 644 "/app/$f" "$WORK/graph/$f"
done

bash /app/build-graph.sh 2>&1 | tee "$LOGDIR/graph.${DATE}.log"

# /graph and $WORK are separate mounts, and rename(2) returns EXDEV across a mount point even when
# both sides are one filesystem. A plain mv falls back to copy-then-unlink straight onto the name a
# starting OTP opens. Copying to a sibling name under /graph first leaves a rename that stays within
# one mount, which is atomic.
GRAPH="$WORK/graph/graph-$ID.obj"
if [ -f "$GRAPH" ] && [ "$GRAPH" -nt "$PUBLISHED/graph-$ID.obj" ]; then
  cp "$GRAPH" "$PUBLISHED/.graph-$ID.obj.tmp"
  mv "$PUBLISHED/.graph-$ID.obj.tmp" "$PUBLISHED/graph-$ID.obj"
  echo "published $(date '+%F %T'): graph-$ID.obj, $(stat -c %s "$PUBLISHED/graph-$ID.obj") bytes"
fi

# Three versions stay published, so an OTP rolled back to an older image still finds its graph.
ls -t "$PUBLISHED"/graph-*.obj 2>/dev/null | tail -n +4 | xargs -r rm -f --
find "$WORK/graph" -maxdepth 1 \( -name 'streetGraph-*.obj' -o -name 'graph-*.obj' \) \
  ! -name "*-$ID.obj" -delete
