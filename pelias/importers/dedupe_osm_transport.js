// SPDX-FileCopyrightText: 2026 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

// The Pelias API dedupe treats the custom `stops` layer like `venue`: an OSM
// airport/station with the same name as an OTP stop and an address postcode
// wins and hides the stop. Find those OSM venues, keep their names as aliases
// of the stop and delete them from Elasticsearch.

let fs = require('fs');

const DATA_DIR = __dirname + "/../data/csv-importer";
const TRANSPORT_STOPS_FILE = DATA_DIR + "/transport-stops.json";
const ALIASES_FILE = DATA_DIR + "/osm-transport-aliases.json";

const ELASTICSEARCH_URL = `http://${process.env.ELASTICSEARCH_HOST || 'localhost'}:9200`;
const DRY_RUN = process.env.DRY_RUN === '1';

// OSM venue category -> compatible stop modes and max distance
const RULES = [
    { category: "transport:air", modes: ["AIRPLANE"], radius: 5000 },
    { category: "transport:rail", modes: ["RAIL", "SUBWAY", "TRAM"], radius: 1000 },
];

// Same normalisation as the API dedupe
function normalize(str) {
    return String(str).normalize("NFKD").replace(/[\u0300-\u036f]/g, "")
        .toLowerCase().split(/[ ,-.]+/).join(" ").trim();
}

function haversineDistance(lat1, lon1, lat2, lon2) {
    const rad = (x) => x * Math.PI / 180;
    const h = Math.sin(rad(lat2 - lat1) / 2) ** 2 +
        Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lon2 - lon1) / 2) ** 2;
    return 2 * 6371000 * Math.asin(Math.sqrt(h));
}

async function es(path, body, contentType = "application/json") {
    let res = await fetch(ELASTICSEARCH_URL + path, {
        method: body === undefined ? "GET" : "POST",
        headers: { "Content-Type": contentType },
        body: body == null ? undefined : (typeof body === "string" ? body : JSON.stringify(body)),
    });
    if (!res.ok) {
        throw new Error(`Elasticsearch HTTP ${res.status} on ${path}: ${await res.text()}`);
    }
    return res.json();
}

async function fetchOsmVenues(category) {
    let venues = [];
    let page = await es("/pelias/_search?scroll=1m", {
        size: 5000,
        _source: ["name", "center_point"],
        query: { bool: { filter: [
            { term: { source: "openstreetmap" } },
            { term: { layer: "venue" } },
            { term: { category: category } },
        ] } },
    });
    while (page.hits.hits.length > 0) {
        venues = venues.concat(page.hits.hits);
        page = await es("/_search/scroll", { scroll: "1m", scroll_id: page._scroll_id });
    }
    await fetch(ELASTICSEARCH_URL + "/_search/scroll", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ scroll_id: page._scroll_id }),
    });
    return venues;
}

async function main() {
    let stops = JSON.parse(fs.readFileSync(TRANSPORT_STOPS_FILE));
    let aliases = fs.existsSync(ALIASES_FILE) ? JSON.parse(fs.readFileSync(ALIASES_FILE)) : {};
    let toDelete = [];

    for (const rule of RULES) {
        // Index compatible stops by normalised name
        let stopsByName = {};
        stops.filter((st) => st.vehicleMode.some((vm) => rule.modes.includes(vm))).forEach((st) => {
            let key = normalize(st.name);
            (stopsByName[key] = stopsByName[key] || []).push(st);
        });

        let venues = await fetchOsmVenues(rule.category);
        let matches = 0;
        venues.forEach((venue) => {
            let names = [...new Set(Object.values(venue._source.name).flat())];
            let point = venue._source.center_point;
            let stop = null;
            for (const name of names) {
                stop = (stopsByName[normalize(name)] || [])
                    .find((st) => haversineDistance(point.lat, point.lon, st.lat, st.lon) <= rule.radius);
                if (stop) { break; }
            }
            if (!stop) { return; }

            // Keep the OSM names the stop doesn't already have
            let known = new Set([normalize(stop.name), ...(aliases[stop.gtfsId] || []).map(normalize)]);
            let newNames = names.filter((n) => !known.has(normalize(n)));
            aliases[stop.gtfsId] = [...new Set((aliases[stop.gtfsId] || []).concat(newNames))];
            toDelete.push(venue._id);
            matches++;

            if (rule.category === "transport:air") {
                let d = Math.round(haversineDistance(point.lat, point.lon, stop.lat, stop.lon));
                let label = [].concat(venue._source.name.default || names)[0];
                console.log(`  ${venue._id} "${label}" -> ${stop.gtfsId} "${stop.name}" (${d}m)`);
            }
        });
        console.log(`${rule.category}: ${venues.length} OSM venues, ${matches} duplicates of a stop`);
    }

    if (DRY_RUN) {
        console.log(`DRY_RUN: would delete ${toDelete.length} OSM venues, aliases not written`);
        return;
    }

    // Persist aliases first: once deleted, the OSM venues are gone for later runs
    fs.writeFileSync(ALIASES_FILE, JSON.stringify(aliases, null, 2));
    console.log(`Wrote aliases for ${Object.keys(aliases).length} stops to ${ALIASES_FILE}`);

    for (let i = 0; i < toDelete.length; i += 1000) {
        let body = toDelete.slice(i, i + 1000)
            .map((id) => JSON.stringify({ delete: { _index: "pelias", _id: id } }))
            .join("\n") + "\n";
        let result = await es("/_bulk", body, "application/x-ndjson");
        if (result.errors) {
            let failed = result.items.filter((it) => it.delete.error);
            throw new Error(`Bulk delete failed for ${failed.length} docs, first: ${JSON.stringify(failed[0].delete.error)}`);
        }
    }
    await es("/pelias/_refresh", null);
    console.log(`Deleted ${toDelete.length} OSM venues duplicating a stop`);
}

main().catch((e) => {
    console.error("Failed to dedupe OSM transport venues:", e);
    process.exit(1);
});
