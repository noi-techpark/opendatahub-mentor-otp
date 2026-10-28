// SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

let fs = require('fs');

// Retrieve all stops and additional information
let GraphqlOtp = require('./graphql_otp.js');
    
// Config
const DATA_DIR = __dirname + "/../data/csv-importer";
const EXPORT_STOPS = DATA_DIR + "/stops.json";
const EXPORT_TRANSPORT_STOPS = DATA_DIR + "/transport-stops.json";
const EXPORT_PARKINGS = DATA_DIR + "/parkings.json";
const EXPORT_RENTAL_VEHICLES = DATA_DIR + "/rental_vehicles.json";
const EXPORT_RENTAL_STATIONS = DATA_DIR + "/rental_stations.json";

// Create folder if not exists
if (!fs.existsSync(DATA_DIR)){
    fs.mkdirSync(DATA_DIR);
}

// Query stops from OTP graphql endpoint
const OTP_GRAPH_URL = process.env.OTP_GRAPH_URL || 'https://v2.otp.opendatahub.com/otp/gtfs/v1';
console.log(`Using OTP instance: ${OTP_GRAPH_URL}`);
GraphqlOtp.query(OTP_GRAPH_URL, GraphqlOtp.queries.getAllPoi)
    .then((data) => {
        if (!data.data) {
            throw new Error(`OTP GraphQL error: ${JSON.stringify(data.errors)}`);
        }
        if (data.errors) {
            console.warn(`OTP returned ${data.errors.length} GraphQL errors, first: ${data.errors[0].message}`);
        }
        let stops = data.data.stops;
        let stations = data.data.stations;
        let vehicleParkings = data.data.vehicleParkings;
        let rentalVehicles = data.data.rentalVehicles;
        let vehicleRentalStations = data.data.vehicleRentalStations;

        if (!stops || stops.length === 0) {
            throw new Error("OTP returned no stops");
        }
        processStops(stops, stations);
        processVehicleParkings(vehicleParkings);
        processRentalVehicles(rentalVehicles);
        processVehicleRentalStations(vehicleRentalStations);
    })
    .catch((e) => {
        console.error("Failed to fetch POI from OTP:", e);
        process.exit(1);
    });


function processStops(stops, stations) {
    // Index stations by id
    let stationsIndex = {};
    stations.forEach((st) => {
        stationsIndex[st.gtfsId] = st;
    });
    
    // Filter stop without routes
    var stops = stops.filter((st) => {
       return st.routes.length > 0;
    })
    // Recompute vehicle mode based on routes
    .map((st) => {
        let vehicleModes = st.routes.map((r) => r.mode);
        // Make it unique
        vehicleModes = [...new Set(vehicleModes)];
        return {
            ...st,
            vehicleMode: vehicleModes,
        };
    // Extract platform into stations
    }).filter((st) => {
        if(st.parentStation) {
            let parentStation = stationsIndex[st.parentStation.gtfsId];
            if(!parentStation.childs) {
                parentStation.childs = [];
            }
            parentStation.childs.push(st);
            return false;
        }
        return true;
    });

    // filter stations with no childs
    stations = stations.filter((st) => {
        return st.childs && st.childs.length > 0;
    });

    // broadcast vehicles from childs to parent station
    stations.forEach((st) => {
        if(st.childs) {
            let vehicleMode = [];
            let routes = [];
            st.childs.forEach((child) => {
                vehicleMode = vehicleMode.concat(child.vehicleMode);
                routes = routes.concat(child.routes);
            });
            vehicleMode = [...new Set(vehicleMode)];
            routes = [...new Set(routes)];
            st.vehicleMode = vehicleMode;
            st.routes = routes;
        }
    });

    let poi = stops.concat(stations);

    /* Modes
    "[\"AIRPLANE\"]"
    "[\"BUS\",\"FUNICULAR\"]"
    "[\"BUS\",\"GONDOLA\",\"RAIL\"]"
    "[\"BUS\",\"GONDOLA\"]"
    "[\"BUS\",\"RAIL\"]"
    "[\"BUS\"]"
    "[\"FUNICULAR\"]"
    "[\"GONDOLA\"]"
    "[\"RAIL\",\"BUS\"]"
    "[\"RAIL\"]"
*/

    // Compute popularity based on number of routes / different vehicle modes
    // Target is between 1'000 and 3'000 for small/medium stops and 3'000-10'000 for big stations
    poi.forEach((p) => {
        let popularity = 0;
        
        if(p.vehicleMode.includes("AIRPLANE")) {
            popularity += 20000;
        }
        if(p.vehicleMode.includes("RAIL")) {
            popularity += 5000;
        }
        if(p.vehicleMode.includes("BUS")) {
            popularity += 500;
        }
        if(p.vehicleMode.includes("TRAM")) {
            popularity += 500;
        }
        if(p.vehicleMode.includes("GONDOLA")) {
            popularity += 500;
        }
        if(p.vehicleMode.includes("FUNICULAR")) {
            popularity += 500;
        }

        popularity += p.routes.length * 250;

        p.popularity = popularity
    });

    // Compute categories based on vehicleMode
    poi.forEach((p) => {
        p.categories = ["public_transport", "public_transport:stop"];
        p.vehicleMode.forEach((vm) => {
            p.categories.push("public_transport:stop:" + vm.toLowerCase());
        });
    });
    
    // Save the processed points
    let fd = fs.openSync(EXPORT_STOPS, "w");
    fs.writeSync(fd, "[\n");
    poi.forEach((p, i) => {
        fs.writeSync(fd, (i > 0 ? ",\n" : "") + JSON.stringify(p, null, 2));
    });
    fs.writeSync(fd, "\n]\n");
    fs.closeSync(fd);
    console.log(`Wrote ${poi.length} stops to ${EXPORT_STOPS}`);

    // Compact air/rail subset used by dedupe_osm_transport.js, which can't
    // parse the full stops.json (too large for a single string)
    const transportModes = ["AIRPLANE", "RAIL", "SUBWAY", "TRAM"];
    let transportStops = poi
        .filter((p) => p.vehicleMode.some((vm) => transportModes.includes(vm)))
        .map((p) => ({ gtfsId: p.gtfsId, name: p.name, lat: p.lat, lon: p.lon, vehicleMode: p.vehicleMode }));
    fs.writeFileSync(EXPORT_TRANSPORT_STOPS, JSON.stringify(transportStops));
    console.log(`Wrote ${transportStops.length} air/rail stops to ${EXPORT_TRANSPORT_STOPS}`);

}

function processVehicleParkings(vehicleParkings) {
    fs.writeFileSync(EXPORT_PARKINGS, JSON.stringify(vehicleParkings, null, 2));
}

function processRentalVehicles(rentalVehicles) {
    // NONE for now
}

function processVehicleRentalStations(vehicleRentalStations) {
    fs.writeFileSync(EXPORT_RENTAL_STATIONS, JSON.stringify(vehicleRentalStations, null, 2));
}  
