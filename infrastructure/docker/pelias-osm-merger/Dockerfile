# SPDX-FileCopyrightText: 2026 NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: AGPL-3.0-or-later

# Merges the Italy + Switzerland OSM extracts into a single pbf before the
# Pelias OSM importer (and `pelias prepare`) reads it — prepare only handles one
# .pbf. osmium-tool does the merge.

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends osmium-tool \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /pelias

COPY pelias/importers/ importers/

ENV OSM_DATAPATH=/data/openstreetmap

CMD ["sh", "importers/merge_osm.sh"]
