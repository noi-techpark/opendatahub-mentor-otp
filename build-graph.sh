#!/bin/bash

# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# Drives pipeline/ from the source feeds to a graph.obj. Runs in the otp-graph-build container,
# where cron reaches it through run-build.sh, and on a developer's machine from the repository root.
#
# Everything worth keeping between runs lives under $WORK -- the source feeds, the LMDB stores, the
# download validators and OTP's base directory. run-build.sh publishes the finished graph out of it.
#
# The Austrian feeds need MV_USERNAME and MV_PASSWORD in the environment; the pipeline reads them
# itself and never stores them.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# The container takes its environment from compose. A developer takes it from .env, which stays
# outside the image.
#
# Read line by line. Compose's .env holds unquoted values, so sourcing
# `OTP_JAVA_OPTS=-XX:+UseZGC -XX:+ZUncommit ...` runs -XX:+ZUncommit as a command. The parsing here
# is Compose's own: one split on the first =, and a matched pair of surrounding quotes removed.
if [ -f "$HERE/.env" ]; then
  while IFS='=' read -r key value || [ -n "$key" ]; do
    case "$key" in '' | '#'*) continue ;; esac
    value=${value%$'\r'}
    case "$value" in
    '"'*'"' | "'"*"'") value=${value:1:-1} ;;
    esac
    export "$key=$value"
  done < "$HERE/.env"
fi

# Defaults are the repository layout. In the image the work directory is a volume and the scripts
# sit apart from the pipeline, so the environment carries these values.
PIPELINE_DIR="${PIPELINE_DIR:-$HERE/pipeline}"
OTP_DOCKER="${OTP_DOCKER:-$HERE/infrastructure/docker/otp-graph-build/otp-docker.sh}"
WORK="${WORK:-$HERE/work}"
TOOLKIT_JAR="$WORK/netex-toolkit-shaded.jar"

: "${OTP_IMAGE:?must name the OTP image that both builds and serves the graph}"

mkdir -p "$WORK"

# ROOT stays the checkout: the pipeline derives its scripts/ and geo/ from it. The state directories
# are the ones that have to outlive the container, and only they move to the work volume.
#
# otp_run is the pipeline's one OTP command line; overriding it sends the OTP phases to
# otp-docker.sh. OTP_JAR is emptied with it: OTP arrives as $OTP_IMAGE, so there is no jar for the
# pipeline to find, and an empty value drops its existence check.
run_make() {
  make -C "$PIPELINE_DIR" -j"${JOBS:-${MAKE_JOBS:-6}}" \
    INPUT_DIR="$WORK/input" \
    DATA_DIR="$WORK/data" \
    STATE_DIR="$WORK/state" \
    OTP_DIR="$WORK/graph" \
    TOOLKIT_JAR="$TOOLKIT_JAR" \
    OTP_JAR= \
    OTP_DOCKER="$OTP_DOCKER" \
    "otp_run=\$(OTP_DOCKER) \$(1) \"\$(2)\" \"\$(3)\"" \
    "$@"
}

# The toolkit jar, when the volume holds a different version from the one asked for. The pipeline
# fetches it only when the file is missing, so on a volume that already holds one a TOOLKIT_VERSION
# bump never arrives; download-toolkit is the pipeline's replace-what-is-there target. The wanted
# version is read back out of the Makefile, so setting nothing leaves the pipeline's own default in
# force. TOOLKIT_VERSION reaches the Makefile from the environment, which its ?= yields to.
#
# TOOLKIT_VERSION is unset when nothing was asked for. Compose passes TOOLKIT_VERSION= for a
# variable it has no value for, and make treats a defined-but-empty environment variable as a value:
# ?= yields to it and the release URL loses its tag.
[ -n "${TOOLKIT_VERSION:-}" ] || unset TOOLKIT_VERSION
TOOLKIT_WANT=$(make -C "$PIPELINE_DIR" --no-print-directory print-TOOLKIT_VERSION)
TOOLKIT_STAMP="$WORK/toolkit-version.stamp"
if [ -f "$TOOLKIT_JAR" ] && [ "$(cat "$TOOLKIT_STAMP" 2>/dev/null)" != "$TOOLKIT_WANT" ]; then
  echo "Toolkit $TOOLKIT_WANT wanted, $(cat "$TOOLKIT_STAMP" 2>/dev/null || echo 'an unrecorded version') on the volume -- replacing"
  run_make download-toolkit
fi
printf '%s\n' "$TOOLKIT_WANT" > "$TOOLKIT_STAMP"

# Named goals stand for themselves: make runs the stages asked for, and the refresh and the OSM
# update below are skipped. This is how a caller reaches one phase -- `build-graph.sh street`.
if [ $# -gt 0 ]; then
  run_make "$@"
  exit 0
fi

# Keep the Europe extract current from the OSM replication diffs. The region extract is re-cut
# afterwards. A cold volume has no PBF to update, and make fetches one during the build below.
#
# pyosmium-up-to-date exits 1 when it stopped at its own size limit with diffs still outstanding,
# and 2 or more on error, so 1 is the signal to go round again.
#
# OSM_UPDATE=0, no, off or false skips this step.
EUROPE_PBF="$WORK/input/europe.osm.pbf"
case "${OSM_UPDATE:-}" in
0 | no | off | false) OSM_UPDATE_WANTED=no ;;
*) OSM_UPDATE_WANTED=yes ;;
esac

if [ ! -f "$EUROPE_PBF" ]; then
  : # nothing to update; make fetches the extract during the build below
elif [ "$OSM_UPDATE_WANTED" = no ]; then
  echo "Leaving $(basename "$EUROPE_PBF") alone (OSM_UPDATE=${OSM_UPDATE})"
else
  echo "Updating $(basename "$EUROPE_PBF") from the OSM replication diffs"
  until pyosmium-up-to-date "$EUROPE_PBF"; do
    status=$?
    [ "$status" -eq 1 ] || exit "$status"
  done
  run_make osm-extract
fi

# The refresh asks every publisher whether its feed moved and keeps the file, and its timestamp,
# wherever the bytes come back unchanged. A plain `make all` never re-downloads anything, so this is
# the only thing that brings new data in.
#
# Austria goes first and alone. Every Verbund target performs its own password grant against the
# same DBP account, and Keycloak's quick-login check rejects logins for one user that arrive closer
# together than a second, as a 401 invalid_grant that reads exactly like a wrong password. In
# parallel the Austrian targets lose that race.
#
# The rest are named one by one here. This list has to stay in step with the pipeline's
# download-feeds target, which also covers Austria.
JOBS=1 run_make download-austria
run_make download-swiss download-trenitalia download-sta download-rap download-flixbus download-parking

run_make all
