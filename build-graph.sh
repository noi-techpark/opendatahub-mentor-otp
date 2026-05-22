#!/bin/bash

# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -euo pipefail
set -a && source .env && set +a

echo building graph with OTP image $OTP_IMAGE

CURL_PROGRESS="--no-progress-meter"
[ -t 1 ] && CURL_PROGRESS="-#"
CURL="curl --location --fail --show-error ${CURL_PROGRESS}"

# OSM
EUROPE_URL=https://download.geofabrik.de/europe-latest.osm.pbf
EUROPE_PBF=data/europe.osm.pbf
SWITZERLAND_SOUTH_TYROL_PBF=data/switzerland-south-tyrol.osm.pbf
# elevation
# this URL is way too overloaded, so we mirror it
# ELEVATION_URL=https://srtm.csi.cgiar.org/wp-content/uploads/files/srtm_5x5/TIFF/srtm_39_03.zip
ELEVATION_URL=https://leonard.io/srtm/srtm_39_03.zip
ELEVATION_ZIP=data/srtm_39_03.zip
# transit data

TRENITALIA_NETEX_URL=https://www.cciss.it/nap/mmtis/public/api/v1/download/blob/Asset/1080596/checkedResource
TRENITALIA_NETEX_XML=data/trenitalia.netex.xml
TRENITALIA_NETEX_GZ=${TRENITALIA_NETEX_XML}.gz
TRENITALIA_NETEX_ZIP=data/trenitalia.netex.zip

# parking
# Override the transmodel API host if needed
PARKING_NETEX_URL=${TRANSMODEL_HOST:-https://transmodel.api.opendatahub.com}/netex/parking
PARKING_NETEX_XML=data/shared-data.xml
PARKING_NETEX_ZIP=data/parking-netex.xml.zip

mkdir -p data

if [ ! -f "${EUROPE_PBF}" ]; then
  echo "Downloading OSM data for Europe from ${EUROPE_URL}"
  ${CURL} ${EUROPE_URL} -o ${EUROPE_PBF}
else
  echo "Checking for updates for existing OSM file"
  pyosmium-up-to-date ${EUROPE_PBF}
fi

# cut out South Tyrol from the large North East Italy extract
if [ ! -f "${SWITZERLAND_SOUTH_TYROL_PBF}" ] || [ "${EUROPE_PBF}" -nt "${SWITZERLAND_SOUTH_TYROL_PBF}" ]; then
  echo "Extracting ${SWITZERLAND_SOUTH_TYROL_PBF} from ${EUROPE_PBF}"
  osmium extract ${EUROPE_PBF} --polygon switzerland-south-tyrol.geojson -o ${SWITZERLAND_SOUTH_TYROL_PBF} --overwrite
fi

if [ ! -f "${ELEVATION_ZIP}" ]; then
  ${CURL} ${ELEVATION_URL} -o ${ELEVATION_ZIP}
  unzip -o ${ELEVATION_ZIP} -d data
fi

# download parking data and put it into a zip
rm -f ${PARKING_NETEX_XML} ${PARKING_NETEX_ZIP}
${CURL} ${PARKING_NETEX_URL} -o ${PARKING_NETEX_XML}

zip --junk-paths ${PARKING_NETEX_ZIP} ${PARKING_NETEX_XML}

rm -f ${TRENITALIA_NETEX_GZ} ${TRENITALIA_NETEX_XML}
echo "Downloading Trenitalia NeTEx transit data from ${TRENITALIA_NETEX_URL}"
${CURL} "${TRENITALIA_NETEX_URL}" -o ${TRENITALIA_NETEX_GZ}
gunzip --stdout ${TRENITALIA_NETEX_GZ} > ${TRENITALIA_NETEX_XML}
zip ${TRENITALIA_NETEX_ZIP} ${TRENITALIA_NETEX_XML}
rm -f ${TRENITALIA_NETEX_GZ} ${TRENITALIA_NETEX_XML}

# actually do graph build
VOLUME_MOUNT="${OTP_GRAPH_VOLUME:-$(pwd)}"
docker run \
  --name otp-graph-build \
  --init \
  --restart no \
  -v "${VOLUME_MOUNT}:/var/opentripplanner/:z" \
  --rm \
  -e JAVA_TOOL_OPTIONS="-Xmx25G -XX:+UseContainerSupport -XX:+UseCompactObjectHeaders" \
  "${OTP_IMAGE}" --abortOnUnknownConfig --build --save


