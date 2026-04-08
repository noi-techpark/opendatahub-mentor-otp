#!/bin/bash
# SPDX-FileCopyrightText: 2026 NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: AGPL-3.0-or-later
#
#
# Must be run from the release directory (where docker-compose.yml lives).
#
# Refer to the ./pelias/README.md on how to run the setup. 
# This script is just to automate that
#
set -euo pipefail

#   PELIAS_CLI_DIR       Directory of the cloned pelias/docker CLI repo
PELIAS_CLI_DIR="${PELIAS_CLI_DIR:-/opt/pelias/cli}"
#   COMPOSE_PROJECT_NAME Docker Compose project name (default: pelias)
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-pelias}"

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

log "Preparing CSV data (OTP stops and NOI Datahub POI)..."
docker compose run --rm pelias-opendatahub-importer

# ── Import data ────────────────────────────────────────────────────────────────

if [ "${FIRST_RUN}" = "true" ]; then
    log "Importing all geodata into Elasticsearch..."
    pelias import all
else
    log "Removing stale stops and POI from Elasticsearch..."
    docker compose run --rm pelias-opendatahub-importer sh importers/delete_old_poi_and_stops.sh

    log "Re-importing CSV data..."
    pelias import csv
fi

# ── Start / restart runtime services ──────────────────────────────────────────

log "Starting Pelias runtime services..."
docker compose up -d \
    elasticsearch libpostal api placeholder interpolation pip

log "Pelias setup complete."
