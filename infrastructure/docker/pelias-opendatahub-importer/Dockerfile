# SPDX-FileCopyrightText: 2026 NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: AGPL-3.0-or-later

FROM node:18-alpine

RUN apk add --no-cache curl jq bash

WORKDIR /pelias

COPY pelias/importers/ importers/

CMD ["sh", "-c", "mkdir -p data/csv-importer && sh importers/download_and_prepare_stops.sh && sh importers/download_and_prepare_poi.sh"]
