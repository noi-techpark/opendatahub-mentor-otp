# SPDX-FileCopyrightText: 2026 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

# Transform stops.json into the csv-importer CSV.
# Names of OSM venues replaced by dedupe_osm_transport.js (if any) are added
# to each stop as aliases via the name_json column.

set -euo pipefail

STOP_JSON_FILE=./data/csv-importer/stops.json
STOP_CSV=./data/csv-importer/stops.csv
ALIASES_FILE=./data/csv-importer/osm-transport-aliases.json

if [ ! -f "$ALIASES_FILE" ]; then
    ALIASES_FILE=$(mktemp)
    echo '{}' > "$ALIASES_FILE"
fi

echo "source,layer,id,name,name_json,lat,lon,popularity,category_json,addendum_json_stop" > $STOP_CSV
jq --raw-output --slurpfile aliases "$ALIASES_FILE" '.[] | ["otp","stops",.gtfsId,.name,(($aliases[0][.gtfsId] // [])|tostring),.lat,.lon,.popularity,(.categories|tostring),(.|tostring)] | @csv' $STOP_JSON_FILE >> $STOP_CSV
