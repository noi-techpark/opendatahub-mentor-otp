# SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
#
# SPDX-License-Identifier: MIT

function delete_old_stops {
  curl -X POST "localhost:9200/pelias/_delete_by_query?pretty" -H 'Content-Type: application/json' -d'
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
  curl -X POST "localhost:9200/pelias/_delete_by_query?pretty" -H 'Content-Type: application/json' -d'
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
  curl -X POST "localhost:9200/pelias/_delete_by_query?pretty" -H 'Content-Type: application/json' -d'
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