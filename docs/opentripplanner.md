<!--
SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>

SPDX-License-Identifier: CC0-1.0
-->

# OpenTripPlanner

This project uses the regular upstream version of OpenTripPlanner with no modifications: 
https://github.com/opentripplanner/OpenTripPlanner

@leonardehrenfried has lots of experience upstreaming code, and it's recommended to not fork OTP
itself but send all code upstream.

## Services

defined in docker-compose.yml

```otp``` run a new instance of OTP by /data

## Building graphs

Github Actions builds fully usable container images that contain both the OTP binaries and the 
prepared graph. These are meant for deployment to a production server.

However, if you want to build a graph locally, you can use the following commands:

```
./build-graph.sh
./run-otp.sh
```

### Specifics

OTP takes the following inputs for graph building:

- OSM
- elevation
- NeTEx and GTFS

As OSM data we download the entire [Europe extract](https://download.geofabrik.de/europe.html) and
use `osmium` to cut out the region. The exact boundaries are defined in
`pipeline/geo/switzerland-italy.geojson`, and `build-graph.sh` keeps the extract current with
`pyosmium-up-to-date`, which applies the OSM replication diffs.

The NeTEx sources are listed in `pipeline/Makefile` and documented in
`pipeline/docs/datasources.md`. They are converted to EPIP and merged into a single archive, which
reaches OTP as one feed; `build-config.json` names that archive. The GTFS feeds and the parking
NeTEx are named there directly.

The graph build itself runs OTP in a container started from `$OTP_IMAGE` -- the same image that
serves the graph, because OTP refuses a `graph.obj` written by a different build.

## Execute OTP instance

```bash
docker-compose up otp
```

After the graph has been built, the planner is available at port *8080*.

### Environment variables

To pass JVM configuration parameters, use the environment variable `JAVA_TOOL_OPTIONS`, for example
`JAVA_TOOL_OPTIONS='-Xmx4g'`.

