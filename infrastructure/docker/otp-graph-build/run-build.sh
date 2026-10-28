#!/bin/bash
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# What cron runs: the configs OTP reads, the build, and the handover of the graph to serving OTP.
#
#     run-build.sh nightly             OSM update, feed refresh, then whatever is out of date
#     run-build.sh deploy              on container start: only what the deployed change needs
#     run-build.sh deploy --dry-run    print that decision and make -n, change nothing
#
# A deploy rebuilds from the inputs on the volume, against what the last finished build used:
#
#     polygon changed     OSM update + region re-cut, then street and graph
#     pipeline changed    the NeTEx stores from input/, then graph
#     OTP image changed   street and graph

set -e
set -o pipefail

MODE=${1:-}
DRY_RUN=
case "$MODE" in nightly | deploy) ;; *) echo "usage: $0 nightly|deploy [--dry-run]" >&2; exit 2 ;; esac
[ "${2:-}" = --dry-run ] && DRY_RUN=1

DATE="$(date +%Y%m%d_%H%M%S)"

WORK=/build
PUBLISHED=/graph
PIPELINE_DIR=${PIPELINE_DIR:-/pipeline}
LOGDIR="$WORK/log"
mkdir -p "$LOGDIR" "$WORK/graph"

# One build or rollout at a time: a rollout runs two serving heaps for a while.
if [ -z "$DRY_RUN" ]; then
  exec 9>"$WORK/.build.lock"
  flock -n 9 || { echo "waiting for the build or rollout in progress"; flock 9; }
  export BUILD_LOCK_HELD=1
fi

# --pull never: the deploy pulls the serving image on the host, with the registry login this container
# does not have.
ID=$(docker run --rm --pull never --entrypoint java "$OTP_IMAGE" \
  -cp @/app/jib-classpath-file @/app/jib-main-class-file --serializationVersionId)
export OTP_SERIALIZATION_ID=$ID
echo "OTP $OTP_IMAGE writes serialization version $ID"

# Before the build too: an image whose graph already exists goes live without waiting for one.
rollout() { [ -n "$DRY_RUN" ] || /app/otp-rollout.sh || echo "rollout failed; the running OTP stays"; }
rollout
trap rollout EXIT

# Retain only the last 14 days of logs
find "$LOGDIR" -type f -mtime +14 -delete

# The configs OTP reads, into its base directory. install -C leaves a file whose contents already
# match untouched, timestamp included: build-config.json is an ordinary prerequisite of
# streetGraph-<id>.obj, the other two of graph-<id>.obj, so a fresh timestamp forces that rebuild.
for f in build-config.json otp-config.json router-config.json; do
  if [ -n "$DRY_RUN" ]; then
    [ "$(cat "/app/$f")" = "$(cat "$WORK/graph/$f" 2>/dev/null)" ] || echo "would install a changed $f"
  else
    install -C -m 644 "/app/$f" "$WORK/graph/$f"
  fi
done

# What the last finished build was made from. A missing stamp is a first run, and counts as
# unchanged.
fp_geo=$(cat "$PIPELINE_DIR"/geo/*.geojson | sha256sum | cut -d' ' -f1)
fp_pipeline=$( (
  cd "$PIPELINE_DIR"
  find Makefile scripts -path scripts/tests -prune -o -type f -print | LC_ALL=C sort | xargs sha256sum
  echo "toolkit ${TOOLKIT_VERSION:-}"
) | sha256sum | cut -d' ' -f1)
# The base OTP the serving image is built on.
fp_otp=$(docker image inspect --format '{{index .Config.Labels "org.opentripplanner.base"}}' "$OTP_IMAGE")

changed() {
  local stamp="$WORK/deployed.$1"
  [ -f "$stamp" ] && [ "$(cat "$stamp")" != "$2" ]
}
record() { [ -n "$DRY_RUN" ] || echo "$2" >"$WORK/deployed.$1"; }

OSM_UPDATE=0
REFRESH=0
[ "$MODE" = nightly ] && OSM_UPDATE=1 REFRESH=1
if changed geo "$fp_geo"; then
  echo "The OSM polygon changed: OSM update and region re-cut"
  OSM_UPDATE=1
fi
if changed pipeline "$fp_pipeline"; then
  echo "The pipeline changed: the NeTEx stores are rebuilt from input/"
  [ -n "$DRY_RUN" ] || bash /app/build-graph.sh clean-netex
  # Recorded now: a retry after a failed build resumes the stores.
  record pipeline "$fp_pipeline"
fi
# A new serialization version names new graphs, which make builds unprompted. A new image of the
# same version needs its street graph removed.
if changed otp "$fp_otp"; then
  echo "The OTP image changed: street and graph"
  [ -n "$DRY_RUN" ] || rm -f "$WORK/graph/streetGraph-$ID.obj"
  record otp "$fp_otp"
fi

if [ -n "$DRY_RUN" ]; then
  echo "OSM_UPDATE=$OSM_UPDATE REFRESH=$REFRESH, then:"
  bash /app/build-graph.sh -n all
  exit 0
fi

OSM_UPDATE=$OSM_UPDATE REFRESH=$REFRESH bash /app/build-graph.sh 2>&1 | tee "$LOGDIR/graph.${DATE}.log"

# The polygon only now: until the region is re-cut, the next run has to see it as changed.
record geo "$fp_geo"
record pipeline "$fp_pipeline"
record otp "$fp_otp"

# /graph and $WORK are separate mounts, and rename(2) returns EXDEV across a mount point even when
# both sides are one filesystem. A plain mv falls back to copy-then-unlink straight onto the name a
# starting OTP opens. Copying to a sibling name under /graph first leaves a rename that stays within
# one mount, which is atomic.
GRAPH="$WORK/graph/graph-$ID.obj"
if [ -f "$GRAPH" ] && [ "$GRAPH" -nt "$PUBLISHED/graph-$ID.obj" ]; then
  cp "$GRAPH" "$PUBLISHED/.graph-$ID.obj.tmp"
  mv "$PUBLISHED/.graph-$ID.obj.tmp" "$PUBLISHED/graph-$ID.obj"
  echo "published $(date '+%F %T'): graph-$ID.obj, $(stat -c %s "$PUBLISHED/graph-$ID.obj") bytes"
fi

# Three versions stay published, so an OTP rolled back to an older image still finds its graph.
ls -t "$PUBLISHED"/graph-*.obj 2>/dev/null | tail -n +4 | xargs -r rm -f --
find "$WORK/graph" -maxdepth 1 \( -name 'streetGraph-*.obj' -o -name 'graph-*.obj' \) \
  ! -name "*-$ID.obj" -delete
