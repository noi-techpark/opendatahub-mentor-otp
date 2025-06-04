#!/bin/bash -e

# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

source .otp-version

CURL="curl --location --fail --show-error -#"

# OSM
NORTH_EAST_URL=https://download.geofabrik.de/europe/italy/nord-est-latest.osm.pbf
NORTH_EAST_PBF=data/italy-nord-est.osm.pbf
SOUTH_TYROL_PBF=data/south-tyrol.osm.pbf
# elevation
# this URL is way too overloaded, so we mirror it
# ELEVATION_URL=https://srtm.csi.cgiar.org/wp-content/uploads/files/srtm_5x5/TIFF/srtm_39_03.zip
ELEVATION_URL=https://leonard.io/srtm/srtm_39_03.zip
ELEVATION_ZIP=data/srtm_39_03.zip
# transit data
TRANSIT_NETEX_URL="https://rapuser:rappass@web01.sta.bz.it/netex/api/v4/downloadVersion?level=1&agencyCode=IT-ITH1"
TRANSIT_NETEX_XML=data/sta-netex.xml
TRANSIT_NETEX_GZ=${TRANSIT_NETEX_XML}.gz
TRANSIT_NETEX_ZIP=${TRANSIT_NETEX_XML}.zip

# parking
PARKING_NETEX_URL=https://transmodel.api.opendatahub.com/netex/parking
PARKING_NETEX_XML=data/shared-data.xml
PARKING_NETEX_ZIP=data/parking-netex.xml.zip

# when on github actions then install the required tools
if [ -n "${CI+isset}" ]; then
  sudo apt-get -qq install osmium-tool pyosmium wget zip
fi

mkdir -p data

if [ ! -f "${NORTH_EAST_PBF}" ]; then
  echo "Downloading OSM data for NE Italy from ${NORTH_EAST_URL}"
  ${CURL} ${NORTH_EAST_URL} -o ${NORTH_EAST_PBF}
else
  echo "Checking for updates for existing OSM file"
  pyosmium-up-to-date ${NORTH_EAST_PBF}
fi

# cut out South Tyrol from the large North East Italy extract
echo "Extracting ${SOUTH_TYROL_PBF} from ${NORTH_EAST_PBF}"
osmium extract ${NORTH_EAST_PBF} --polygon south-tyrol.geojson -o ${SOUTH_TYROL_PBF} --overwrite

if [ ! -f "${ELEVATION_ZIP}" ]; then
  ${CURL} ${ELEVATION_URL} -o ${ELEVATION_ZIP}
  unzip -o ${ELEVATION_ZIP} -d data
fi

rm -f ${TRANSIT_NETEX_GZ} ${TRANSIT_NETEX_XML}
echo "Downloading NeTEx transit data from ${TRANSIT_NETEX_URL}"
${CURL} "${TRANSIT_NETEX_URL}" -o ${TRANSIT_NETEX_GZ}
gunzip ${TRANSIT_NETEX_GZ}
zip ${TRANSIT_NETEX_ZIP} ${TRANSIT_NETEX_XML}

# download parking data and put it into a zip
rm -f ${PARKING_NETEX_XML} ${PARKING_NETEX_ZIP}
${CURL} ${PARKING_NETEX_URL} -o ${PARKING_NETEX_XML}

zip --junk-paths ${PARKING_NETEX_ZIP} ${PARKING_NETEX_XML}


# actually do graph build
docker run \
  -v .:/var/opentripplanner/:z \
  --rm \
  -e JAVA_TOOL_OPTIONS="-Xmx6G" \
  "${OTP_IMAGE}" --abortOnUnknownConfig --build --save
