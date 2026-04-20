#!/bin/sh
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -e

# On first start (no prior successful build on this image version), run immediately in background
if [ ! -f /run/.build-initialized ]; then
  (/build/run-build.sh && touch /run/.build-initialized) &
fi

# propagate current env variables to cron job
printenv > /etc/environment
# Set up the cron job to rebuild every night
echo "0 2 * * * root /build/run-build.sh" > /etc/cron.d/build-graph 
chmod 0644 /etc/cron.d/build-graph

# Start cron
cron

# Start nginx in foreground
exec nginx -g "daemon off;"
