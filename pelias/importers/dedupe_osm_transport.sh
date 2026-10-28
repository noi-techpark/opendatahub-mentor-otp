# SPDX-FileCopyrightText: 2026 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

# Replace OSM air/rail venues that duplicate an OTP stop: move their names to
# the stop as aliases and delete them from Elasticsearch.

set -euo pipefail

node ./importers/dedupe_osm_transport.js
sh ./importers/stops_to_csv.sh
