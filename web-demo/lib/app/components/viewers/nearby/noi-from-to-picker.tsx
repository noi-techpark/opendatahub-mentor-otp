import { connect } from 'react-redux'
import { Place } from '@opentripplanner/types'
import FromToLocationPicker from '@opentripplanner/from-to-location-picker'
import React, { useCallback, useMemo } from 'react'

import * as mapActions from '@otp-react-redux/lib/actions/map'
import { SetLocationHandler } from '@otp-react-redux/lib/components/util/types'
import { MapboxGeoJSONFeature } from 'react-map-gl'
import { setQueryParam } from '../../../../vendor/otp-react-redux/lib/actions/form'

interface Props {
  className?: string
  place: Place | MapboxGeoJSONFeature 
  setLocation: SetLocationHandler
  handlePlanTripClick: () => void
}

const NoiFromToPicker = ({ className, place, setLocation, handlePlanTripClick }: Props) => {
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
          setLocation({ location, locationType: 'to', reverseGeocode: false })
          console.log(place);
          setQueryParam({ entityId: place.properties?.id })
        }, [location, setLocation])}
      />
    </span>
  )
}

const mapDispatchToProps = {
  setLocation: mapActions.setLocation
}

export default connect(null, mapDispatchToProps)(NoiFromToPicker)
