// SPDX-FileCopyrightText: 2024 routeRANK <info@routerank.com>
//
// SPDX-License-Identifier: MIT

let getAllPoi = `query GetAllPoi {
  vehicleParkings {
    vehicleParkingId   
    name
    bicyclePlaces
    carPlaces
    lat
    lon
    note
    tags 
  }
  rentalVehicles {
    vehicleId
    name
    lat
    lon
    rentalNetwork {
      networkId
      url
    }
    vehicleType {
      formFactor
      propulsionType
    }
  }
  vehicleRentalStations {
    stationId
    name
    lat
    lon
    availableSpaces {
      byType {
        count
        vehicleType {
          formFactor
          propulsionType
        }
      }
      total
    }
    availableVehicles {
      byType {
        count
        vehicleType {
          formFactor
          propulsionType
        }
      }
      total
      
    }
    capacity
    rentalNetwork {
      networkId
      url
    }
  }
  stations {
    gtfsId
    lat
    lon
    name
  }
  stops {
    gtfsId
    code
    desc
    lat
    lon
    name
    parentStation {
      gtfsId
      name
    }
    vehicleType
    vehicleMode
    platformCode
    __typename
    url
    routes {
      gtfsId
      mode
      shortName
      longName
      agency {
        name
        url
        gtfsId
      }
    }
  }
}`;

exports.query = function(endpoint, query) {
  return fetch(
    endpoint,
    {
        method: "POST",
        headers: {
        "Content-Type": "application/json",
        },
        body: JSON.stringify({query}),
    }
    ).then((response) => response.json())
}

exports.queries = {
  getAllPoi: getAllPoi
}