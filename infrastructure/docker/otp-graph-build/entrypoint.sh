#!/bin/sh
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -e

# On first start (no prior successful build on this image version), run immediately in background
if [ ! -f /run/.build-initialized ]; then
  (/build/run-build.sh && touch /run/.build-initialized) &
fi

# Start cron
cron

# Start nginx in foreground
exec nginx -g "daemon off;"
