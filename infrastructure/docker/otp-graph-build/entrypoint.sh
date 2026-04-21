#!/bin/sh
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -e

# propagate current env variables to cron job
printenv | sed 's/^\(.*\)=\(.*\)$/export \1="\2"/' > /etc/environment.sh

# Set up the cron job to rebuild every night
echo "0 2 * * * root . /etc/environment.sh && /build/run-build.sh" > /etc/cron.d/build-graph

# If this container has not had a build yet, schedule one immediately on startup
if [ ! -f /run/.build-initialized ]; then
  echo "@reboot root . /etc/environment.sh && /build/run-build.sh && touch /run/.build-initialized" >> /etc/cron.d/build-graph
fi
chmod 0644 /etc/cron.d/build-graph

# Start cron
cron

# Start nginx in foreground
exec nginx -g "daemon off;"
