// SPDX-FileCopyrightText: 2026 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

let fs = require('fs');
const { fetchAllPages } = require('./fetch-odh-paged');

const DATA_DIR = __dirname + "/../data/csv-importer";
const EXPORT_FILE = DATA_DIR + "/discoverswiss-accomodation-poi.json";

const ODH_API_URL = process.env.ODH_API_URL || 'https://tourism.api.opendatahub.com';
const PAGESIZE = process.env.DISCOVERSWISS_PAGESIZE || 1000;

if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR);
}

fetchAllPages(`${ODH_API_URL}/v1/Accommodation?source=discoverswiss`, PAGESIZE).then((items) => {
    fs.writeFileSync(EXPORT_FILE + ".tmp", JSON.stringify(items, null, 2));
    fs.renameSync(EXPORT_FILE + ".tmp", EXPORT_FILE);
    console.log(`Wrote ${items.length} discoverswiss accommodation records to ${EXPORT_FILE}`);
}).catch((e) => {
    console.error("Failed to fetch discoverswiss accommodation:", e.message);
    process.exit(1);
});
