#!/bin/bash
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# What cron runs. Owns the log directory, the OTP configuration the build reads, and the handover of
# the finished graph to the serving container.

set -e
set -o pipefail

DATE="$(date +%Y%m%d_%H%M%S)"

WORK=/build
LOGDIR="$WORK/log"
mkdir -p "$LOGDIR" "$WORK/graph"

# Retain only the last 14 days of logs
find "$LOGDIR" -type f -mtime +14 -delete

# The configs OTP reads, into its base directory. install -C leaves a file whose contents already
# match untouched, timestamp included: build-config.json is an ordinary prerequisite of
# streetGraph-<id>.obj, the other two of graph-<id>.obj, so a fresh timestamp forces that rebuild.
for f in build-config.json otp-config.json router-config.json; do
  install -C -m 644 "/app/$f" "$WORK/graph/$f"
done

ID=$(docker run --rm --entrypoint java "$OTP_IMAGE" \
  -cp @/app/jib-classpath-file @/app/jib-main-class-file --serializationVersionId)
export OTP_SERIALIZATION_ID=$ID

bash /app/build-graph.sh 2>&1 | tee "$LOGDIR/graph.${DATE}.log"

# /graph and $WORK are separate mounts, and rename(2) returns EXDEV across a mount point even when
# both sides are one filesystem. A plain mv falls back to copy-then-unlink straight onto the name a
# starting OTP opens. Copying to a sibling name under /graph first leaves a rename that stays within
# one mount, which is atomic.
GRAPH="$WORK/graph/graph-$ID.obj"
if [ -f "$GRAPH" ]; then
  cp "$GRAPH" "/graph/.graph-$ID.obj.tmp"
  mv "/graph/.graph-$ID.obj.tmp" "/graph/graph-$ID.obj"
  echo "published $(date '+%F %T'): graph-$ID.obj, $(stat -c %s "/graph/graph-$ID.obj") bytes"
fi
