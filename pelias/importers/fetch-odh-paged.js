// SPDX-FileCopyrightText: 2026 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

// Paginated fetcher for Open Data Hub tourism API list endpoints.
// Usage: node fetch-odh-paged.js <url> <out-file>
// <url> must not contain pagesize/pagenumber; it may contain other query params.
// Writes {"Items":[...]} to <out-file> (atomically, via <out-file>.tmp).

let fs = require('fs');

const DEFAULT_PAGESIZE = 10000;

function envInt(name, def) {
    let v = parseInt(process.env[name], 10);
    return Number.isNaN(v) || v < 0 ? def : v;
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchPage(url, retries, delayMs) {
    let lastError;

    for (let attempt = 0; attempt <= retries; attempt++) {
        if (attempt > 0) {
            console.warn(`Retry ${attempt}/${retries} in ${delayMs}ms for ${url} (${lastError.message})`);
            await sleep(delayMs);
        }
        try {
            let res = await fetch(url);
            if (!res.ok) {
                let body = "";
                try {
                    body = (await res.text()).slice(0, 500);
                } catch (e) { /* ignore */ }
                throw new Error(`HTTP ${res.status} ${res.statusText} for ${url} ${body}`.trim());
            }
            return await res.json();
        } catch (e) {
            lastError = e;
        }
    }
    throw new Error(`Failed after ${retries + 1} attempts: ${lastError.message}`);
}

async function fetchAllPages(url, pagesize) {
    pagesize = pagesize || envInt('ODH_PAGESIZE', DEFAULT_PAGESIZE) || DEFAULT_PAGESIZE;
    const retries = envInt('ODH_RETRIES', 3);
    const delayMs = envInt('ODH_RETRY_DELAY_MS', 10000);

    let items = [];
    let page = 1;
    let totalPages = 1;
    let response = null;

    while (page <= totalPages) {
        let u = new URL(url);
        u.searchParams.set('pagesize', pagesize);
        u.searchParams.set('pagenumber', page);
        console.log(`Fetching page ${page}/${response ? totalPages : "*"}: ${u.toString()}`);
        response = await fetchPage(u.toString(), retries, delayMs);
        totalPages = response.TotalPages || 1;
        for (let item of response.Items || []) {
            items.push(item);
        }
        page++;
    }

    if (response && typeof response.TotalResults === 'number' && items.length !== response.TotalResults) {
        console.warn(`Warning: fetched ${items.length} items but TotalResults is ${response.TotalResults}`);
    }

    return items;
}

module.exports = { fetchAllPages };

if (require.main === module) {
    const [url, outFile] = process.argv.slice(2);
    if (!url || !outFile || process.argv.length > 4) {
        console.error("Usage: node fetch-odh-paged.js <url> <out-file>");
        process.exit(1);
    }
    const tmpFile = outFile + ".tmp";

    fetchAllPages(url).then((items) => {
        let fd = fs.openSync(tmpFile, "w");
        try {
            fs.writeSync(fd, '{"Items":[\n');
            items.forEach((item, i) => {
                fs.writeSync(fd, (i > 0 ? ",\n" : "") + JSON.stringify(item));
            });
            fs.writeSync(fd, "\n]}\n");
        } finally {
            fs.closeSync(fd);
        }
        fs.renameSync(tmpFile, outFile);
        console.log(`Wrote ${items.length} items to ${outFile}`);
    }).catch((e) => {
        console.error("Failed to fetch ODH data:", e.message);
        try {
            fs.rmSync(tmpFile, { force: true });
        } catch (e2) { /* ignore */ }
        process.exit(1);
    });
}
