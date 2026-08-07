#!/bin/bash
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -e
DATE="$(date +%Y%m%d_%H%M%S)"

LOGDIR="/graph/log"
mkdir -p $LOGDIR

# Copy static files needed by build-graph.sh into /graph so relative paths resolve
# and so they are accessible when OTP mounts the volume in its own container
for f in switzerland-italy.geojson \
         build-config.json otp-config.json router-config.json; do
  cp --remove-destination "/build/$f" "/graph/$f"
done

# Retain only the last 14 days of logs
find "$LOGDIR" -type f -mtime +14 -delete

cd /graph
set -o pipefail


# Convert switzerland NeTEx to EPIP format
NETEX_ZIP=/graph/data/switzerland.epip.netex.zip
NETEX_LOG="$LOGDIR/netex.swiss.${DATE}.log"
if [ ! -f "$NETEX_ZIP" ] || [ "$(( $(date +%s) - $(stat -c %Y "$NETEX_ZIP") ))" -gt $((3 * 86400)) ]; then
  OUTPUT_ZIP_FILE="$NETEX_ZIP" bash /build/build-switzerland-netex.sh 2>&1 | tee "$NETEX_LOG"
else
  echo "Skipping Switzerland NeTEx build: $NETEX_ZIP is less than 24h old" | tee "$NETEX_LOG"
fi


# Build STA netex
NETEX_STA_LOG="$LOGDIR/build.netex.sta.${DATE}.log"
OUTPUT_ZIP_FILE="/graph/data/sta.epip.netex.zip" bash /build/build-sta-netex.sh 2>&1 | tee "$LOGDIR/netex.sta.${DATE}.log"


# Build OTP graph
bash /build/build-graph.sh 2>&1 | tee "$LOGDIR/graph.${DATE}.log"

# Atomically publish the new graph via rename so OTP's inotifywait sees a single moved_to
# event only after the build is fully complete, never mid-write
if [ -f /graph/graph.obj ]; then
  mv /graph/graph.obj /graph/graph.obj.publishing
  mv /graph/graph.obj.publishing /graph/graph.obj
fi
