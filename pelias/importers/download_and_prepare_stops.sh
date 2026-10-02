# SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

set -euo pipefail

# fetch JSON data from OpenTripPlanner 
node ./importers/fetch-poi.js

# transform JSON data to CSV
sh ./importers/stops_to_csv.sh
