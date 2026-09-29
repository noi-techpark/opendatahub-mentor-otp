// SPDX-FileCopyrightText: 2026 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

let GraphqlOtp = require('./graphql_otp.js');

const OTP_GRAPH_URL = process.env.OTP_GRAPH_URL || 'https://otp.opendatahub.testingmachine.eu/otp/gtfs/v1';

const query = `query GetStopsLatLon {
  stops {
    gtfsId
    name
    lat
    lon
  }
}`;

console.log(`Using OTP instance: ${OTP_GRAPH_URL}`);
GraphqlOtp.query(OTP_GRAPH_URL, query)
    .then((data) => {
        let stops = data.data.stops.filter((st) => st.lat != null && st.lon != null);

        let north = stops.reduce((a, b) => b.lat > a.lat ? b : a);
        let south = stops.reduce((a, b) => b.lat < a.lat ? b : a);
        let east = stops.reduce((a, b) => b.lon > a.lon ? b : a);
        let west = stops.reduce((a, b) => b.lon < a.lon ? b : a);

        console.log(`Total stops: ${stops.length}`);
        console.log(`\nBounding box:`);
        console.log(`  minLon: ${west.lon}`);
        console.log(`  maxLon: ${east.lon}`);
        console.log(`  minLat: ${south.lat}`);
        console.log(`  maxLat: ${north.lat}`);

        console.log(`\nStops at edges:`);
        console.log(`  North: ${north.name} (${north.gtfsId}) [${north.lat}, ${north.lon}]`);
        console.log(`  South: ${south.name} (${south.gtfsId}) [${south.lat}, ${south.lon}]`);
        console.log(`  East:  ${east.name} (${east.gtfsId}) [${east.lat}, ${east.lon}]`);
        console.log(`  West:  ${west.name} (${west.gtfsId}) [${west.lat}, ${west.lon}]`);
    });
