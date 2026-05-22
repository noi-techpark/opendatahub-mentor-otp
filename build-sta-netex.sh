#!/bin/bash

# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -euo pipefail

CURL="curl --location --fail --show-error -#"

today=$(date +"%Y%m%d")
STA_NETEX_URL="ftp://ftp.sta.bz.it/netex/2026/plan/EU_profil/NX-PI_01_it_apb_LINE_apb__${today}.xml.zip"

INPUT_FILE=sta.netex.zip
OUTPUT_FILE=sta.epip.netex.xml
OUTPUT_ZIP_FILE=${OUTPUT_ZIP_FILE:-sta.epip.netex.zip}

INTERMEDIATE_DB=sta.lmdb
EPIP_DB=sta-epip.lmdb

if [ ! -d "badger" ]; then
  git clone --branch mentz-line-versions https://github.com/leonardehrenfried/badger.git
fi

cd badger
# version that implements the corrected line resolution algorithm
git checkout 7c8bc5909f851b695ab4f9285d1fd6f7edd9b297

uv sync
UV_VENV_CLEAR=1 bash scripts/setup.sh

source .venv/bin/activate

echo "clean up previous build"
rm -Rf ./sta*


${CURL} ${STA_NETEX_URL} -o ${INPUT_FILE}
echo "Starting to rewrite STA NeTEx data (resolving line versions, rewriting SSP IDs)"

uv run python -m conv.netex_to_db ${INPUT_FILE} ${INTERMEDIATE_DB}
uv run python -m fix.resolve_mentz_line_versions ${INTERMEDIATE_DB}
uv run python -m fix.rewrite_sta_ssp_ids ${INTERMEDIATE_DB}
uv run python -m conv.epip_db_to_db ${INTERMEDIATE_DB} ${EPIP_DB}
uv run python -m conv.epip_db_to_xml ${EPIP_DB} ${OUTPUT_FILE}

zip ${OUTPUT_ZIP_FILE} ${OUTPUT_FILE} --junk-paths