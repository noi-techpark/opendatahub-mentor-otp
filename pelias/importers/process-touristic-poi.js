// SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

const fs = require('fs');

const DATA_DIR = __dirname + "/../data/csv-importer";
const touristicAll = require(DATA_DIR + '/touristic-poi-all.json');
const touristicFilteredSet = require(DATA_DIR + '/touristic-poi-filtered-set.json');

const accomodationAll = require(DATA_DIR + '/accomodation-poi-all.json');
const accomodationFilteredSet = require(DATA_DIR + '/accomodation-poi-filtered-set.json');

const EXPORT_POIS = DATA_DIR + "/touristic-poi.json";
const EXPORT_ACCOMODATION = DATA_DIR + "/accomodation-poi.json";
// Index POI by id
const touristicAllIndex = {};
touristicAll.Items.forEach((item) => {
    touristicAllIndex[item.Id] = item;
});


var touristicPois = touristicFilteredSet.Items.map((filteredItem) => {
    // Retrieve poi data
    let poi = touristicAllIndex[filteredItem.Id];
    
    if(!poi) {
        console.log("Missing poi", filteredItem);
    }

    return poi;
}).filter((poi) => {
    return poi;
});

// Write poi to file
fs.writeFileSync(EXPORT_POIS, JSON.stringify(touristicPois, null, 2));
delete touristicPois;
delete touristicAllIndex;

// Index accomodation by id
const accomodationIndex = {};
accomodationAll.Items.forEach((item) => {
    accomodationIndex[item.Id] = item;
});

var accomodationPois = accomodationFilteredSet.Items.map((filteredItem) => {
    // Retrieve poi data
    let poi = accomodationIndex[filteredItem.Id];
    
    if(!poi) {
        console.log("Missing poi", filteredItem);
    }

    return poi;
}).filter((poi) => {
    return poi;
});

// Write poi to file
fs.writeFileSync(EXPORT_ACCOMODATION, JSON.stringify(accomodationPois, null, 2));