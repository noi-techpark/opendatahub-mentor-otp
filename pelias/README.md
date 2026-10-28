<!--
SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>

SPDX-License-Identifier: MIT
-->

# Italy / Switzerland area

This project is configured to download/prepare/build a complete Pelias installation for all of Italy and all of Switzerland.

# Setup

Please refer to the instructions at <https://github.com/pelias/docker> in order to install and configure your docker environment.

The minimum configuration required in order to run this project are [installing prerequisites](https://github.com/pelias/docker#prerequisites), [install the pelias command](https://github.com/pelias/docker#installing-the-pelias-command) and [configure the environment](https://github.com/pelias/docker#configure-environment)

You also need `curl`, `jq` and `node` to import OpenTripPlanner stops, NOI Datahub Activities and Accomodations POI, and Swiss accommodation POI from the Open Data Hub `discoverswiss` source.

Please ensure that's all working fine before continuing.

# Run a Build

To run a complete build, execute the following commands:

## Pull relevant images and create elasticsearch shard (see pelias.json configuration)
```bash
pelias compose pull
pelias elastic start
pelias elastic wait
pelias elastic create
```

## Download all the relevant information

```bash
pelias download all
./importers/merge_osm.sh
pelias prepare all
./importers/download_and_prepare_stops.sh
./importers/download_and_prepare_poi.sh
```

OSM downloads two extracts, `italy-latest.osm.pbf` and `switzerland-latest.osm.pbf`, and `merge_osm.sh` merges them into a single `italy-switzerland.osm.pbf` (which `openstreetmap.import` targets). It must run **before `pelias prepare`**: prepare builds polylines/interpolation from only one `.pbf` and would otherwise warn "multiple .pbf files found" and cover just one region. `pelias download all` re-fetches the raw pbfs each run, so re-run the merge after every download. (Needs `osmium-tool` installed.)

`merge_osm.sh` also filters the merged pbf down to a highway-only extract (`data/openstreetmap-polylines/streets.osm.pbf`) for the `polylines` prepare step: `pelias/polylines` hard-refuses any single `.pbf` over 1GB and the merged Italy+Switzerland file is ~2.6GB. In the containerised (prod/local-release) setup the `polylines` service mounts this filtered file over `/data/openstreetmap`, so it only ever sees the small extract; the full merged pbf is untouched for the `openstreetmap` importer/interpolation. In dev, mount `${DATA_DIR}/openstreetmap-polylines` the same way (already wired in `docker-compose.yml`).

## Import all the data in the service

```bash
pelias import wof
pelias import oa
pelias import osm
pelias import polylines
pelias import csv
```

## Frequently update stops and POI
```bash
./importers/download_and_prepare_stops.sh
./importers/download_and_prepare_poi.sh
./importers/delete_old_poi_and_stops.sh
pelias import csv
```

# Starting the service
The API service can then be started with the following command

```bash
pelias compose up -d
```

# Make an Example Query

You can now make queries against your new Pelias build:

<http://localhost:4000/v1/search?text=Morena>
