# SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

ELASTICSEARCH_HOST="${ELASTICSEARCH_HOST:-localhost}"

function delete_old_stops {
  curl -X POST "${ELASTICSEARCH_HOST}:9200/pelias/_delete_by_query?pretty" -H 'Content-Type: application/json' -d'
  {
    "query": {
      "match": {
        "source": "otp"
      }
    }
  }
  '
}
function delete_old_poi {
  curl -X POST "${ELASTICSEARCH_HOST}:9200/pelias/_delete_by_query?pretty" -H 'Content-Type: application/json' -d'
  {
    "query": {
      "match": {
        "source": "noi-datahub-poi"
      }
    }
  }
  '
}
function delete_old_accomodation {
  curl -X POST "${ELASTICSEARCH_HOST}:9200/pelias/_delete_by_query?pretty" -H 'Content-Type: application/json' -d'
  {
    "query": {
      "match": {
        "source": "noi-datahub-accomodation"
      }
    }
  }
  '
}

delete_old_stops
delete_old_poi
delete_old_accomodation