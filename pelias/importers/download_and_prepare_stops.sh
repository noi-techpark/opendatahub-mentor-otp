# SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

STOP_JSON_FILE=./data/csv-importer/stops.json
STOP_CSV=./data/csv-importer/stops.csv


# fetch JSON data from OpenTripPlanner 
node ./importers/fetch-poi.js

# transform JSON data to CSV with jq
echo "source,layer,id,name,lat,lon,popularity,categories,addendum_json_stop" > $STOP_CSV
cat $STOP_JSON_FILE | jq --raw-output '.[] | ["otp","stops",.gtfsId,.name,.lat,.lon,.popularity,(.categories|tostring),(.|tostring)] | @csv' >> $STOP_CSV
