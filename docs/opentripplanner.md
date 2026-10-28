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

The graph build itself runs OTP in a container started from the serving image, which
`infrastructure/docker/otp/Dockerfile` builds on the base OTP its `ARG BASE_IMAGE` names. Building
and serving from one image matters because OTP refuses a graph written by a different
serialization version. Both graphs carry that version in their names, `streetGraph-<id>.obj` and
`graph-<id>.obj`, through the `${otp.serialization.version.id}` that `build-config.json` uses: a new
OTP builds graphs of its own, and each server loads the one it can read.

## Deployment

OTP serves from a compose project of its own, `<project>-otp`, which the deploy playbook does not
take down. Its compose file ships in each release as `docker-compose.otp.yml`, so when the deploy
role reverts a failed release, the restored `otp-graph-build` rolls OTP back with it. Traefik
answers on `SERVER_PORT_OTP` and routes only to an OTP whose healthcheck passes, which it does once
its graph is loaded. Every build ends in `otp-rollout.sh`, which starts the OTP image the deploy
named next to the running one, waits for it to load `graph-<id>.obj`, and only then stops the old
one. Until a graph for the new image exists, the old OTP serves the old graph. The newest three
published versions are kept, so an older image still finds its graph. A restart without downtime:

```bash
docker compose -p <project> exec otp-graph-build /app/otp-rollout.sh --force
```

## Execute OTP instance

```bash
docker-compose up otp
```

After the graph has been built, the planner is available at port *8080*.

### Environment variables

To pass JVM configuration parameters, use the environment variable `JAVA_TOOL_OPTIONS`, for example
`JAVA_TOOL_OPTIONS='-Xmx4g'`.

