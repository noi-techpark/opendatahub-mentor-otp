// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import { connect } from 'react-redux'
import { Place } from '@opentripplanner/types'
import FromToLocationPicker from '@opentripplanner/from-to-location-picker'
import React, { useCallback, useMemo } from 'react'

import * as mapActions from '@otp-react-redux/lib/actions/map'
import { SetLocationHandler } from '@otp-react-redux/lib/components/util/types'
import { MapboxGeoJSONFeature } from 'react-map-gl'
import { setQueryParam } from '@otp-react-redux/lib/actions/form'
import { routingQuery } from '@otp-react-redux/lib/actions/api'

interface Props {
  query: any
  className?: string
  place: Place | MapboxGeoJSONFeature 
  setLocation: SetLocationHandler
  handlePlanTripClick: () => void
  routingQuery: () => void
}

const NoiFromToPicker = ({ className, place, setLocation, handlePlanTripClick, query, routingQuery }: Props) => {
  const location = useMemo(
    () => ({
      lat: place.lat ?? place.geometry.coordinates[1],
      lon: place.lon ?? place.geometry.coordinates[0],
      name: place.name ?? place.properties.name,
      properties: place.properties
    }),
    [place]
  )
  return (
    <span className={className} role="group">
      <FromToLocationPicker
        label
        onFromClick={useCallback(() => {
          handlePlanTripClick && handlePlanTripClick()
          setLocation({ location, locationType: 'from', reverseGeocode: false })
        }, [location, setLocation])}
        onToClick={useCallback(() => {
          let from = query.to;
          handlePlanTripClick && handlePlanTripClick()
          //setLocation({ location: null, locationType: 'to', reverseGeocode: false })
          setLocation({ location: from, locationType: 'from', reverseGeocode: false })
          setLocation({ location, locationType: 'to', reverseGeocode: false })
          setTimeout(() => {routingQuery(), 100});
          //setQueryParam({ entityId: place.properties?.id })

        }, [location, setLocation])}
      />
    </span>
  )
}

const mapDispatchToProps = {
  setLocation: mapActions.setLocation,
  routingQuery: routingQuery
}

const mapStateToProps = (state) => {
  return {
    query: state.otp.currentQuery
  };
}

export default connect(mapStateToProps, mapDispatchToProps)(NoiFromToPicker)
