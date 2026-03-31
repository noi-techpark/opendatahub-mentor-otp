#!/bin/bash
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -e

LOG="/graph/build.$(date +%Y%m%d_%H%M%S).log"

# Copy static files needed by build-graph.sh into /graph so relative paths resolve
# and so they are accessible when OTP mounts the volume in its own container
for f in .otp-version switzerland-south-tyrol.geojson transform-scheduled-stop-point-ids.xsl \
         build-config.json otp-config.json router-config.json; do
  cp --remove-destination "/build/$f" "/graph/$f"
done

cd /graph
bash /build/build-graph.sh 2>&1 | tee "$LOG"

# Retain only the 10 most recent log files
ls -t /graph/build.*.log | tail -n +11 | xargs -r rm -f

# Atomically publish the new graph via rename so OTP's inotifywait sees a single moved_to
# event only after the build is fully complete, never mid-write
if [ -f /graph/graph.obj ]; then
  mv /graph/graph.obj /graph/graph.obj.publishing
  mv /graph/graph.obj.publishing /graph/graph.obj
fi
