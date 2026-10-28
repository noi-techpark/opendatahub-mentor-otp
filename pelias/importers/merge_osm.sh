# SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

# Merge the Italy and Switzerland OSM extracts into a single pbf before import.

set -e

OSM_DATAPATH="${OSM_DATAPATH:-./data/openstreetmap}"
ITALY_PBF="$OSM_DATAPATH/italy-latest.osm.pbf"
CH_PBF="$OSM_DATAPATH/switzerland-latest.osm.pbf"
COMBINED_PBF="$OSM_DATAPATH/italy-switzerland.osm.pbf"

POLYLINES_SRC_DIR="$(dirname "$OSM_DATAPATH")/openstreetmap-polylines"
POLYLINES_SRC_PBF="$POLYLINES_SRC_DIR/streets.osm.pbf"
HIGHWAY_TAGS="motorway,primary,residential,road,secondary,service,tertiary,trunk"

if [ -f "$ITALY_PBF" ] && [ -f "$CH_PBF" ]; then
  osmium merge "$ITALY_PBF" "$CH_PBF" -o "$COMBINED_PBF" -O

  # keep only the merged file in the datapath so prepare sees exactly one .pbf
  rm -f "$ITALY_PBF" "$CH_PBF"

  mkdir -p "$POLYLINES_SRC_DIR"
  osmium tags-filter "$COMBINED_PBF" "w/highway=$HIGHWAY_TAGS" -o "$POLYLINES_SRC_PBF" -O
else
  echo "merge_osm: source pbfs not both present; assuming already merged, skipping."
fi
