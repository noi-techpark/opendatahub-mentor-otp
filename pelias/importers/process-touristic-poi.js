// SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

const fs = require('fs');

const DATA_DIR = __dirname + "/../data/csv-importer";

// Each POI type is processed independently so a failed download of one
// type does not block the other.
const TYPES = {
    touristic: {
        all: DATA_DIR + '/touristic-poi-all.json',
        filtered: DATA_DIR + '/touristic-poi-filtered-set.json',
        out: DATA_DIR + '/touristic-poi.json',
    },
    accomodation: {
        all: DATA_DIR + '/accomodation-poi-all.json',
        filtered: DATA_DIR + '/accomodation-poi-filtered-set.json',
        out: DATA_DIR + '/accomodation-poi.json',
    },
};

const type = process.argv[2];
const config = TYPES[type];
if (!config) {
    console.error("Usage: node process-touristic-poi.js <" + Object.keys(TYPES).join("|") + ">");
    process.exit(1);
}

const all = JSON.parse(fs.readFileSync(config.all, 'utf8'));
const filteredSet = JSON.parse(fs.readFileSync(config.filtered, 'utf8'));

// Index POI by id
const allIndex = {};
all.Items.forEach((item) => {
    allIndex[item.Id] = item;
});

const pois = filteredSet.Items.map((filteredItem) => {
    // Retrieve poi data
    const poi = allIndex[filteredItem.Id];

    if (!poi) {
        console.log("Missing poi", filteredItem);
    }

    return poi;
}).filter((poi) => {
    return poi;
});

// Write poi to file
fs.writeFileSync(config.out, JSON.stringify(pois, null, 2));
