#!/bin/bash
# SPDX-FileCopyrightText: 2026 NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# pelias-setup.sh — Initialize or incrementally update a Pelias deployment.
#
# Must be run from the release directory (where docker-compose.yml lives).
# Ansible handles this via the chdir parameter.
#
# On first run (no Elasticsearch index found):
#   pull images → start ES → wait → create index →
#   download all geodata → prepare all → download CSV data →
#   import all → start runtime services
#
# On subsequent runs (index exists):
#   pull images → ensure ES running → refresh CSV data
#   (stops + POI) → delete old entries → re-import CSV →
#   restart runtime services
#
# Environment variables:
#   PELIAS_CLI_DIR       Directory of the cloned pelias/docker CLI repo
#                        (default: /opt/pelias/cli)
#   COMPOSE_PROJECT_NAME Docker Compose project name (default: pelias)

set -euo pipefail

PELIAS_CLI_DIR="${PELIAS_CLI_DIR:-/opt/pelias/cli}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-pelias}"

# Importers and config files live alongside this script in the release dir.
IMPORTERS_DIR="./importers"

export PATH="${PELIAS_CLI_DIR}:${PATH}"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"; }

# ── Sanity checks ─────────────────────────────────────────────────────────────

if ! command -v pelias > /dev/null 2>&1; then
    echo "ERROR: 'pelias' CLI not found. Ensure PELIAS_CLI_DIR is set correctly." >&2
    exit 1
fi

# ── Pull latest images ─────────────────────────────────────────────────────────

log "Pulling latest Pelias images..."
pelias compose pull

# ── Ensure Elasticsearch is running ───────────────────────────────────────────

log "Starting Elasticsearch..."
pelias elastic start

log "Waiting for Elasticsearch to be ready..."
pelias elastic wait

# ── Detect first run ──────────────────────────────────────────────────────────
# The Pelias index is created only during full initialization.
# If it is missing we treat this as a first run.

if ! curl -sf "http://localhost:9200/pelias" > /dev/null 2>&1; then
    FIRST_RUN=true
    log "Pelias index not found — running full initialization."
else
    FIRST_RUN=false
    log "Pelias index found — running incremental CSV update."
fi

# ── Full initialization (first run only) ──────────────────────────────────────

if [ "${FIRST_RUN}" = "true" ]; then
    log "Creating Elasticsearch index..."
    pelias elastic create

    log "Downloading all geodata (may take a long time)..."
    pelias download all

    log "Preparing polylines, placeholder and interpolation databases..."
    pelias prepare all
fi

# ── Prepare CSV data (stops + POI) ────────────────────────────────────────────
# The importer scripts reference __dirname/../data/csv-importer (Node.js) and
# ./data/csv-importer/ (shell scripts).  We mount the importers at
# /pelias/importers and the pelias-data volume at /pelias/data so that both
# path patterns resolve to /pelias/data/csv-importer inside the container.

log "Preparing CSV data (OTP stops and NOI Datahub POI)..."
docker run --rm \
    -v "${COMPOSE_PROJECT_NAME}_pelias-data:/pelias/data" \
    -v "$(pwd)/${IMPORTERS_DIR#./}:/pelias/importers:ro" \
    -w /pelias \
    node:18-alpine \
    sh -c "
        apk add --no-cache curl jq &&
        mkdir -p data/csv-importer &&
        sh importers/download_and_prepare_stops.sh &&
        sh importers/download_and_prepare_poi.sh
    "

# ── Import data ────────────────────────────────────────────────────────────────

if [ "${FIRST_RUN}" = "true" ]; then
    log "Importing all geodata into Elasticsearch..."
    pelias import all
else
    log "Removing stale stops and POI from Elasticsearch..."
    sh "${IMPORTERS_DIR}/delete_old_poi_and_stops.sh"

    log "Re-importing CSV data..."
    pelias import csv
fi

# ── Start / restart runtime services ──────────────────────────────────────────

log "Starting Pelias runtime services..."
docker compose up -d \
    elasticsearch libpostal api placeholder interpolation pip

log "Pelias setup complete."
