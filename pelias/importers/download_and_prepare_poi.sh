# SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

# Each POI source (touristic, accomodation, discoverswiss) is independent: if
# one fails, a WARNING is logged, its previous CSV is kept (header-only CSV on
# a first run) and the script still exits 0.

set -eu
set -o pipefail

ODH_API_URL="${ODH_API_URL:-https://tourism.api.opendatahub.com}"
DATA=./data/csv-importer

TOURISTIC_CSV=$DATA/touristic-poi.csv
ACCOMODATION_CSV=$DATA/accomodation-poi.csv
DISCOVERSWISS_ACCOMODATION_CSV=$DATA/discoverswiss-accomodation-poi.csv

TOURISTIC_HEADER="source,layer,id,lat,lon,name,name_de,name_en,name_fr,name_it,category_json,addendum_json_poi"
ACCOMODATION_HEADER="source,layer,id,lat,lon,name,name_de,name_en,name_fr,name_it,category_json,addendum_json_accomodation"

log() {
    echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"
}

mkdir -p "$DATA"

prepare_touristic() {
    node ./importers/fetch-odh-paged.js "$ODH_API_URL/v1/ODHActivityPoi" $DATA/touristic-poi-all.json || return 1
    node ./importers/fetch-odh-paged.js "$ODH_API_URL/v1/STA/ODHActivityPoi?language=en&referer=SuedtirolMobilWeb&fields=Id%2CDetail.en.Title%2CContactInfos.en.City" $DATA/touristic-poi-filtered-set.json || return 1
    node ./importers/process-touristic-poi.js touristic || return 1

    # transform JSON data to CSV with jq
    echo "$TOURISTIC_HEADER" > $TOURISTIC_CSV.tmp || return 1
    jq --raw-output ".[] | [
    \"noi-datahub-poi\",\"venue\",\
    .Id,\
    .GpsPoints.position.Latitude,.GpsPoints.position.Longitude,\
    .Detail.it.Title,.Detail.de.Title,.Detail.en.Title,.Detail.fr.Title,.Detail.it.Title,\
    ([.Tags[] | \"touristic:\"+ (.Id | gsub(\" \"; \"_\"))] | tostring), \
    (.|tostring) \
    ] | @csv" $DATA/touristic-poi.json >> $TOURISTIC_CSV.tmp || return 1
    mv $TOURISTIC_CSV.tmp $TOURISTIC_CSV || return 1
}

prepare_accomodation() {
    node ./importers/fetch-odh-paged.js "$ODH_API_URL/v1/Accommodation" $DATA/accomodation-poi-all.json || return 1
    node ./importers/fetch-odh-paged.js "$ODH_API_URL/v1/STA/Accommodation?language=en&referer=SuedtirolMobilWeb&fields=Id%2CAccoDetail.en.Name%2CAccoDetail.en.City" $DATA/accomodation-poi-filtered-set.json || return 1
    node ./importers/process-touristic-poi.js accomodation || return 1

    echo "$ACCOMODATION_HEADER" > $ACCOMODATION_CSV.tmp || return 1
    jq --raw-output ".[] | [
    \"noi-datahub-accomodation\",\"venue\",\
    .Id,\
    .Latitude,.Longitude,\
    .AccoDetail.it.Name,.AccoDetail.de.Name,.AccoDetail.en.Name,.AccoDetail.fr.Name,.AccoDetail.it.Name,\
    ([(.AccoType,.AccoCategory) | \"accomodation:\"+ (.Id | gsub(\" \"; \"_\"))] | tostring), \
    (.|tostring) \
    ] | @csv" $DATA/accomodation-poi.json >> $ACCOMODATION_CSV.tmp || return 1
    mv $ACCOMODATION_CSV.tmp $ACCOMODATION_CSV || return 1
}

prepare_discoverswiss() {
    node ./importers/fetch-discoverswiss-accomodation.js || return 1

    echo "$ACCOMODATION_HEADER" > $DISCOVERSWISS_ACCOMODATION_CSV.tmp || return 1
    jq --raw-output ".[] | [
    \"discoverswiss-accomodation\",\"venue\",\
    .Id,\
    .Latitude,.Longitude,\
    .AccoDetail.it.Name,.AccoDetail.de.Name,.AccoDetail.en.Name,.AccoDetail.fr.Name,.AccoDetail.it.Name,\
    ([(.AccoType,.AccoCategory) | \"accomodation:\"+ (.Id | gsub(\" \"; \"_\"))] | tostring), \
    (.|tostring) \
    ] | @csv" $DATA/discoverswiss-accomodation-poi.json >> $DISCOVERSWISS_ACCOMODATION_CSV.tmp || return 1
    mv $DISCOVERSWISS_ACCOMODATION_CSV.tmp $DISCOVERSWISS_ACCOMODATION_CSV || return 1
}

FAILED=""

# run_source <func> <csv> <header>
run_source() {
    func=$1
    csv=$2
    header=$3
    log "$func: starting"
    if "$func"; then
        log "$func: OK"
    else
        rm -f "$csv.tmp"
        if [ -s "$csv" ]; then
            echo "WARNING: $func failed, keeping previous $csv (last updated $(date -r "$csv"))" >&2
        else
            echo "WARNING: $func failed and no previous $csv exists, writing header-only CSV" >&2
            echo "$header" > "$csv"
        fi
        FAILED="$FAILED $func"
    fi
}

run_source prepare_touristic "$TOURISTIC_CSV" "$TOURISTIC_HEADER"
run_source prepare_accomodation "$ACCOMODATION_CSV" "$ACCOMODATION_HEADER"
run_source prepare_discoverswiss "$DISCOVERSWISS_ACCOMODATION_CSV" "$ACCOMODATION_HEADER"

if [ -n "$FAILED" ]; then
    echo "WARNING: POI sources failed (previous data kept):$FAILED" >&2
    log "done with failures:$FAILED"
else
    log "done, all POI sources OK"
fi
exit 0
