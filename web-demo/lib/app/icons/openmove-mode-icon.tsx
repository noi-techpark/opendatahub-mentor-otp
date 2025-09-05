// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later

import React from "react";

import {
  Bicycle,
  BicycleRide,
  BikeSharing,
  BikeSharingRide,
  Bus,
  Car,
  CarSharing,
  CarSharingRide,
  Funicolar,
  ParkRide,
  Micromobility,
  MicromobilityRide,
  Rail,
  Walk
} from "./";

function OpenMoveModeIcon({ mode, ...props }) {
  if (!mode) return null;
  switch (mode.toLowerCase()) {
    case "bicycle":
      return <Bicycle {...props} />
    case "bicycle_ride":
      return <BicycleRide {...props} />
    case "bicycle_rent":      
      return <BikeSharing {...props} />;
    case "bicycle_rent_ride":      
      return <BikeSharingRide {...props} />;
    case "car_rent":
      return <CarSharing {...props} />;
    case "car_rent_ride":
      return <CarSharingRide {...props} />;
    case "car_park_ride":
      return <ParkRide {...props} />;
    case "micromobility":
      return <Micromobility {...props} />;
    case "micromobility_ride":
      return <MicromobilityRide {...props} />;
    case "transit":
      return <Bus {...props} />;
    case "bus":
      return <Bus {...props} />;      
    case "walk":
      return <Walk {...props} />;
    case "gondola":
    case "funicular":
      return <Funicolar {...props} />;
    case "rail":
      return <Rail {...props} />;
    case "car":
      return <Car {...props} />;
    // case "streetcar":
    //   return <Streetcar {...props} />;
    // case "subway":
    // case "tram":
    //   return <Tram {...props} />;
    default:
      return null;
  }
}

export default OpenMoveModeIcon;
