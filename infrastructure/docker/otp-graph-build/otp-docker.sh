#!/bin/bash

# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# The pipeline's OTP call site, redirected into a container. Stands in for its `java -jar` line, so
# the arguments are the ones that line takes: a heap size, OTP's own arguments, and the JVM flags
# for the build phases.
#
#     otp-docker.sh 50G "--abortOnUnknownConfig --loadStreet --save" "-XX:MaxGCPauseMillis=500"
#
# OTP is run from $OTP_IMAGE, the image that also serves the graph: OTP refuses a graph.obj written
# by a different build.

set -euo pipefail

heap=$1
otp_args=$2
jvm=${3:-}

: "${OTP_IMAGE:?must name the OTP image}"
: "${OTP_WORK_VOLUME:?must name the volume holding the OTP base directory}"

# The -v source is a volume name the host's daemon resolves: this runs against that daemon over the
# mounted socket, where a path inside this container names nothing.
#
# OTP's base directory is spelled out below, because the override in build-graph.sh drops the
# $(OTP_DIR) the pipeline appends: the work volume lands at /var/opentripplanner/ in the spawned
# container, and graph/ under it is the directory the pipeline calls $(OTP_DIR).
#
# --entrypoint java, because the image's own entrypoint is
#
#    java $JAVA_OPTS -cp @/app/jib-classpath-file @/app/jib-main-class-file /var/opentripplanner/ $@
#
# which fixes the base directory at the mount point and appends whatever it is given.
exec docker run \
  --rm \
  --init \
  --name otp-graph-build-otp \
  -v "${OTP_WORK_VOLUME}:/var/opentripplanner/:z" \
  -e JAVA_TOOL_OPTIONS="-Xmx${heap} -Xms${heap} ${jvm} -XX:+UseContainerSupport" \
  --entrypoint java \
  "${OTP_IMAGE}" \
  -cp @/app/jib-classpath-file @/app/jib-main-class-file \
  /var/opentripplanner/graph ${otp_args}
