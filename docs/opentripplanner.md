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
- GTFS

As OSM data we download the entire [North East of Italy](https://download.geofabrik.de/europe/italy/nord-est.html)
and use `osmium` to cut out South Tyrol. The exact boundaries are defined in `south-tyrol.geojson`.

The GTFS feeds to use are defined in `build-config.json`.

## Execute OTP instance

```bash
docker-compose up otp
```

After the graph has been built, the planner is available at port *8080*.

### Environment variables

To pass JVM configuration parameters, use the environment variable `JAVA_TOOL_OPTIONS`, for example
`JAVA_TOOL_OPTIONS='-Xmx4g'`.

