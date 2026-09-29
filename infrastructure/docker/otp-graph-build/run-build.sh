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
# streetGraph.obj, the other two of graph.obj, so a fresh timestamp forces that rebuild.
for f in build-config.json otp-config.json router-config.json; do
  install -C -m 644 "/app/$f" "$WORK/graph/$f"
done

bash /app/build-graph.sh 2>&1 | tee "$LOGDIR/graph.${DATE}.log"

# Hand the new graph to the serving container, which watches /graph for it.
#
# /graph and $WORK are separate mounts, and rename(2) returns EXDEV across a mount point even when
# both sides are one filesystem. A plain mv falls back to copy-then-unlink straight onto the watched
# path and exposes a partial graph. Copying to a sibling name under /graph first leaves a rename
# that stays within one mount: atomic, and a single moved_to for the watcher.
if [ -f "$WORK/graph/graph.obj" ]; then
  cp "$WORK/graph/graph.obj" /graph/graph.obj.tmp
  mv /graph/graph.obj.tmp /graph/graph.obj
  echo "published $(date '+%F %T'): $(stat -c %s /graph/graph.obj) bytes"
fi
