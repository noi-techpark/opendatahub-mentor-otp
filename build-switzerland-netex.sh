#!/bin/bash -e

# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

CURL="curl --location --fail --show-error -#"

INPUT_FILE=switzerland.netex.zip
OUTPUT_FILE=switzerland.epip.netex.zip

git clone --branch binary_relation_serializer git@github.com:MMTIS/badger.git

cd badger

uv sync
source venv/bin/activate

curl https://data.opentransportdata.swiss/dataset/timetablenetex_2026/permalink -o ${INPUT_FILE}

uv run python -m conv.netex_to_db switzerland.netex.zip switzerland.lmdb
uv run python -m conv.epip_db_to_db switzerland.lmdb switzerland-epip.lmdb
uv run python -m conv.epip_db_to_xml switzerland-epip.lmdb switzerland-epip.xml

zip switzerland.epip.xml.zip ${OUTPUT_FILE} --junk-paths