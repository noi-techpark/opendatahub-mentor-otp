// SPDX-FileCopyrightText: 2026 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

let fs = require('fs');

const DATA_DIR = __dirname + "/../data/csv-importer";
const EXPORT_FILE = DATA_DIR + "/discoverswiss-accomodation-poi.json";

const ODH_API_URL = process.env.ODH_API_URL || 'https://tourism.api.opendatahub.com';
const PAGESIZE = process.env.DISCOVERSWISS_PAGESIZE || 1000;

if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR);
}

async function fetchAllPages() {
    let items = [];
    let page = 1;
    let totalPages = 1;

    while (page <= totalPages) {
        let url = `${ODH_API_URL}/v1/Accommodation?source=discoverswiss&pagesize=${PAGESIZE}&pagenumber=${page}`;
        console.log(`Fetching page ${page}/${totalPages}: ${url}`);
        let response = await fetch(url).then((res) => res.json());
        totalPages = response.TotalPages;
        items = items.concat(response.Items);
        page++;
    }

    return items;
}

fetchAllPages().then((items) => {
    fs.writeFileSync(EXPORT_FILE, JSON.stringify(items, null, 2));
    console.log(`Wrote ${items.length} discoverswiss accommodation records to ${EXPORT_FILE}`);
});
