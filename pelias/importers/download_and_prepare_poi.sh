# SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

curl "https://tourism.api.opendatahub.com/v1/ODHActivityPoi?pagesize=50000" > ./data/csv-importer/touristic-poi-all.json
curl "https://tourism.api.opendatahub.com/v1/STA/ODHActivityPoi?language=en&referer=SuedtirolMobilWeb&fields=Id%2CDetail.en.Title%2CContactInfos.en.City&pagesize=20000" > ./data/csv-importer/touristic-poi-filtered-set.json

curl "https://tourism.api.opendatahub.com/v1/Accommodation?pagesize=20000" > ./data/csv-importer/accomodation-poi-all.json 
curl "https://tourism.api.opendatahub.com/v1/STA/Accommodation?language=en&referer=SuedtirolMobilWeb&fields=Id%2CAccoDetail.en.Name%2CAccoDetail.en.City&pagesize=10000" > ./data/csv-importer/accomodation-poi-filtered-set.json

node ./importers/process-touristic-poi.js

TOURISTIC_CSV=./data/csv-importer/touristic-poi.csv
TOURISTIC_JSON_FILE=./data/csv-importer/touristic-poi.json
ACCOMODATION_CSV=./data/csv-importer/accomodation-poi.csv
ACCOMODATION_JSON_FILE=./data/csv-importer/accomodation-poi.json

# transform JSON data to CSV with jq
echo "source,layer,id,lat,lon,name,name_de,name_en,name_fr,name_it,category_json,addendum_json_poi" > $TOURISTIC_CSV
cat $TOURISTIC_JSON_FILE | jq --raw-output ".[] | [
    \"noi-datahub-poi\",\"venue\",\
    .Id,\
    .GpsPoints.position.Latitude,.GpsPoints.position.Longitude,\
    .Detail.it.Title,.Detail.de.Title,.Detail.en.Title,.Detail.fr.Title,.Detail.it.Title,\
    ([.Tags[] | \"touristic:\"+ (.Id | gsub(\" \"; \"_\"))] | tostring), \
    (.|tostring) \
    ] | @csv" >> $TOURISTIC_CSV;

echo "source,layer,id,lat,lon,name,name_de,name_en,name_fr,name_it,category_json,addendum_json_accomodation" > $ACCOMODATION_CSV
cat $ACCOMODATION_JSON_FILE | jq --raw-output ".[] | [
    \"noi-datahub-accomodation\",\"venue\",\
    .Id,\
    .Latitude,.Longitude,\
    .AccoDetail.it.Name,.AccoDetail.de.Name,.AccoDetail.en.Name,.AccoDetail.fr.Name,.AccoDetail.it.Name,\
    ([(.AccoType,.AccoCategory) | \"accomodation:\"+ (.Id | gsub(\" \"; \"_\"))] | tostring), \
    (.|tostring) \
    ] | @csv" >> $ACCOMODATION_CSV;
