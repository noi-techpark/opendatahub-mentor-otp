#!/bin/sh
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

GRAPH=/var/otp/graph.obj

while true; do
  echo "Waiting for $GRAPH..."
  while [ ! -f "$GRAPH" ]; do sleep 5; done

  echo "graph.obj found, starting OTP"
  GRAPH_MTIME=$(stat -c '%Y' "$GRAPH" 2>/dev/null || echo 0)

  java $JAVA_OPTS -cp @/app/jib-classpath-file @/app/jib-main-class-file /var/otp/ --load --serve &
  OTP_PID=$!

  # Watch for atomic rename (moved_to) and direct overwrite (close_write) to cover both write patterns.
  # Verify mtime changed before breaking to avoid spurious close_write events.
  while true; do
    EVENT=$(inotifywait -e moved_to,close_write --format '%f' /var/otp/ 2>/dev/null)
    if [ "$EVENT" = "graph.obj" ]; then
      NEW_MTIME=$(stat -c '%Y' "$GRAPH" 2>/dev/null || echo 0)
      [ "$NEW_MTIME" != "$GRAPH_MTIME" ] && break
    fi
  done

  echo "graph.obj updated, restarting OTP"
  kill $OTP_PID
  wait $OTP_PID 2>/dev/null || true
done
