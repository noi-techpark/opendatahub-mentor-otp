#!/bin/sh
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

GRAPH=/var/otp/graph.obj

while true; do
  echo "Waiting for $GRAPH..."
  while [ ! -f "$GRAPH" ]; do sleep 5; done

  echo "graph.obj found, starting OTP"
  java $JAVA_OPTS -cp @/app/jib-classpath-file @/app/jib-main-class-file /var/otp/ --load --serve &
  OTP_PID=$!

  # Block until graph.obj is atomically replaced (moved_to fires on rename, not during write)
  while true; do
    EVENT=$(inotifywait -e moved_to --format '%f' /var/otp/ 2>/dev/null)
    [ "$EVENT" = "graph.obj" ] && break
  done

  echo "graph.obj updated, restarting OTP"
  kill $OTP_PID
  wait $OTP_PID 2>/dev/null || true
done
