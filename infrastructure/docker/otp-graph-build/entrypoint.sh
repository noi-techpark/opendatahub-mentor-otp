#!/bin/sh
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -e

# propagate current env variables to cron job
#
# MV_PASSWORD reaches the build through this file, which cron sources. shlex.quote keeps a password
# containing $, " or a backslash intact; unquoted, it arrives expanded or mangled and the Austrian
# downloads fail with an authentication error that names nothing.
python3 -c 'import os, shlex
print("\n".join(f"export {k}={shlex.quote(v)}" for k, v in os.environ.items() if k.isidentifier()))' \
  > /etc/environment.sh
chmod 600 /etc/environment.sh

# Set up the cron job to rebuild every night
echo "0 2 * * * root . /etc/environment.sh && /app/run-build.sh >> /proc/1/fd/1 2>&1" > /etc/cron.d/build-graph

# If this container has not had a build yet, schedule one immediately on startup
if [ ! -f /run/.build-initialized ]; then
  echo "@reboot root . /etc/environment.sh && /app/run-build.sh >> /proc/1/fd/1 2>&1 && touch /run/.build-initialized" >> /etc/cron.d/build-graph
fi
chmod 0644 /etc/cron.d/build-graph

# Start cron
cron

# Start nginx in foreground
exec nginx -g "daemon off;"
