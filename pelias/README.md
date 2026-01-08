<!--
SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>

SPDX-License-Identifier: MIT
-->

# Italian area

This project is configured to download/prepare/build a complete Pelias installation for South-Tyrol.

# Setup

Please refer to the instructions at <https://github.com/pelias/docker> in order to install and configure your docker environment.

The minimum configuration required in order to run this project are [installing prerequisites](https://github.com/pelias/docker#prerequisites), [install the pelias command](https://github.com/pelias/docker#installing-the-pelias-command) and [configure the environment](https://github.com/pelias/docker#configure-environment)

You also need `curl`, `jq` and `node` to import OpenTripPlanner stops and NOI Datahub Activities and Accomodations POI.

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
pelias prepare all
./importers/download_and_prepare_stops.sh
./importers/download_and_prepare_poi.sh
```

## Import all the data in the service
```bash
pelias import all
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
