// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import { connect } from 'react-redux'
import { Place } from '@opentripplanner/types'
import FromToLocationPicker from '@opentripplanner/from-to-location-picker'
import React, { useCallback, useMemo } from 'react'
import { usePlanning } from '../../../context/planning-context'

import {
  setLocation
} from '@otp-react-redux/lib/actions/map'
import {
  clearLocation
} from '@otp-react-redux/lib/actions/form'
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
  clearLocation: (arg: { locationType: 'from' | 'to' }) => void
}

const NoiFromToPicker = ({
  className,
  place,
  setLocation,
  handlePlanTripClick,
  query,
  routingQuery,
  clearLocation
}: Props) => {
  const { setIsPlanning } = usePlanning()
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
        label={false}
        onFromClick={useCallback(() => {
          handlePlanTripClick && handlePlanTripClick()
          clearLocation({ locationType: 'from' })
          clearLocation({ locationType: 'to' })
          setIsPlanning(true)
          setLocation({ location, locationType: 'from', reverseGeocode: false })
        }, [location, setLocation, clearLocation, setIsPlanning])}
        onToClick={useCallback(() => {
          handlePlanTripClick && handlePlanTripClick()
          clearLocation({ locationType: 'from' })
          clearLocation({ locationType: 'to' })
          setIsPlanning(true)
          setLocation({ location, locationType: 'to', reverseGeocode: false })
        }, [location, setLocation, clearLocation, setIsPlanning])}
      />
    </span>
  )
}

const mapDispatchToProps = {
  setLocation,
  clearLocation,
  routingQuery
}

const mapStateToProps = (state) => {
  return {
    query: state.otp.currentQuery
  }
}

export default connect(mapStateToProps, mapDispatchToProps)(NoiFromToPicker)
