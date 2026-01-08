// SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

let fs = require('fs');

// Retrieve all stops and additional information
let GraphqlOtp = require('./graphql_otp.js');
    
// Config
const DATA_DIR = __dirname + "/../data/csv-importer";
const EXPORT_STOPS = DATA_DIR + "/stops.json";
const EXPORT_PARKINGS = DATA_DIR + "/parkings.json";
const EXPORT_RENTAL_VEHICLES = DATA_DIR + "/rental_vehicles.json";
const EXPORT_RENTAL_STATIONS = DATA_DIR + "/rental_stations.json";

// Create folder if not exists
if (!fs.existsSync(DATA_DIR)){
    fs.mkdirSync(DATA_DIR);
}

// Query stops from OTP graphql endpoint
GraphqlOtp.query('https://otp.opendatahub.testingmachine.eu/otp/gtfs/v1', GraphqlOtp.queries.getAllPoi)
    .then((data) => {
        let stops = data.data.stops;
        let stations = data.data.stations;
        let vehicleParkings = data.data.vehicleParkings;
        let rentalVehicles = data.data.rentalVehicles;
        let vehicleRentalStations = data.data.vehicleRentalStations;
        processStops(stops, stations);
        processVehicleParkings(vehicleParkings);
        processRentalVehicles(rentalVehicles);
        processVehicleRentalStations(vehicleRentalStations);
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
            popularity += 1500;
        }
        if(p.vehicleMode.includes("RAIL")) {
            popularity += 1000;
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
    fs.writeFileSync(EXPORT_STOPS, JSON.stringify(poi, null, 2));  

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