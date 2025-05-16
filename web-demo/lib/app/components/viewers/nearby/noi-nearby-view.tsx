// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import { connect } from 'react-redux'
import { FormattedMessage, useIntl } from 'react-intl'
import { Location } from '@opentripplanner/types'
import { MapRef, useMap } from 'react-map-gl'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import * as apiActions from '@otp-react-redux/lib/actions/api'
import * as mapActions from '@otp-react-redux/lib/actions/map'
import * as uiActions from '@otp-react-redux/lib/actions/ui'
import { AppReduxState } from '@otp-react-redux/lib/util/state-types'
import {
  SetLocationHandler,
  ZoomToPlaceHandler
} from '@otp-react-redux/lib/components/util/types'
import Loading from '@otp-react-redux/lib/components/narrative/loading'
import MobileContainer from '@otp-react-redux/lib/components/mobile/container'
import MobileNavigationBar from '@otp-react-redux/lib/components/mobile/navigation-bar'
import PageTitle from '@otp-react-redux/lib/components/util/page-title'
import VehiclePositionRetriever from '@otp-react-redux/lib/components/viewers/vehicle-position-retriever'

import {
  FloatingLoadingIndicator,
  NearbySidebarContainer,
  Scrollable
} from '@otp-react-redux/lib/components/viewers/nearby/styled'
import NoiFromToPicker from './noi-from-to-picker'
import RentalStation from '@otp-react-redux/lib/components/viewers/nearby/rental-station'
import Stop from '@otp-react-redux/lib/components/viewers/nearby/stop'
import Vehicle from '@otp-react-redux/lib/components/viewers/nearby/vehicle-rent'
import VehicleParking from '@otp-react-redux/lib/components/viewers/nearby/vehicle-parking'

const AUTO_REFRESH_INTERVAL = 15000

// TODO: use lonlat package
type LatLonObj = { lat: number; lon: number }
type CurrentPosition = { coords?: { latitude: number; longitude: number } }

type Props = {
  currentQuery?: any,
  defaultLatLon: LatLonObj | null,
  displayedCoords?: LatLonObj
  entityId?: string
  fetchNearby: (latLon: LatLonObj, radius?: number) => void
  hideBackButton?: boolean
  location: string
  mobile?: boolean
  nearby: any
  nearbyViewCoords?: LatLonObj
  radius?: number
  setHighlightedLocation: (location: Location | null) => void
  setLocation: SetLocationHandler
  setMainPanelContent: (content: number) => void
  setViewedNearbyCoords: (location: Location | null) => void
  validLocations: string[]
  zoomToPlace: ZoomToPlaceHandler
  handlePlanTripClick: () => void
}

const getNearbyItem = (place: any, handlePlanTripClick: any) => {
  const fromTo = <NoiFromToPicker handlePlanTripClick={handlePlanTripClick} place={place} />

  switch (place.__typename) {
    case 'RentalVehicle':
      return <Vehicle fromToSlot={fromTo} vehicle={place} />
    case 'Stop':
      return <Stop fromToSlot={fromTo} stopData={{...place, code: place.platformCode}} />
    case 'VehicleParking':
      return <VehicleParking fromToSlot={fromTo} place={place} />
    case 'BikeRentalStation':
      return <RentalStation fromToSlot={fromTo} place={place} />
    default:
      console.warn(
        `Received unsupported nearby place type: ${place.__typename} `
      )
      return null
  }
}

function getNearbyCoordsFromUrlOrLocationOrMapCenter(
  coordsFromUrl?: LatLonObj,
  currentPosition?: CurrentPosition,
  map?: MapRef,
  defaultLatLon?: LatLonObj | null
): LatLonObj | null {
  if (coordsFromUrl) {
    return coordsFromUrl
  }

  if (currentPosition?.coords) {
    const { latitude: lat, longitude: lon } = currentPosition.coords
    return { lat, lon }
  }

  const rawMapCoords = map?.getCenter()
  const mapCoords = rawMapCoords !== undefined && {
    lat: rawMapCoords.lat,
    lon: rawMapCoords.lng
  }
  if (mapCoords) {
    return mapCoords
  }
  if (defaultLatLon) {
    return defaultLatLon
  }
  return null
}

function NoiNearbyView({
  currentQuery,
  defaultLatLon,
  displayedCoords,
  entityId,
  fetchNearby,
  location,
  mobile,
  nearby,
  nearbyViewCoords,
  radius,
  setHighlightedLocation,
  setMainPanelContent,
  setViewedNearbyCoords,
  validLocations,
  zoomToPlace,
  handlePlanTripClick
}: Props): JSX.Element {
  const map = useMap().default
  const intl = useIntl()
  const [loading, setLoading] = useState(true)
  const firstItemRef = useRef<HTMLDivElement>(null)
  
  useEffect(() => {
      if(currentQuery.to) { 
        console.debug("set viewed coords", currentQuery.to);
        setTimeout(() => 
        setViewedNearbyCoords({lat: currentQuery.to.lat, lon: currentQuery.to.lon}), 100);
      }
  }, [currentQuery]);


  const finalNearbyCoords = useMemo(
    () =>
      getNearbyCoordsFromUrlOrLocationOrMapCenter(
        nearbyViewCoords,
        currentQuery.to,
        map,
        defaultLatLon
      ),
    [nearbyViewCoords, currentQuery, map]
  )

  // Make sure the highlighted location is cleaned up when leaving nearby
  useEffect(() => {
    return function cleanup() {
      setHighlightedLocation(null)
    }
  }, [location, setHighlightedLocation])

  useEffect(() => {
    const moveListener = (e: mapboxgl.EventData) => {
      if (e.geolocateSource) {
        setViewedNearbyCoords({
          lat: e.viewState.latitude,
          lon: e.viewState.longitude
        })
      }
    }

    const dragListener = (e: mapboxgl.EventData) => {
      const coords = {
        lat: e.viewState.latitude,
        lon: e.viewState.longitude
      }
      setViewedNearbyCoords(coords)

      // Briefly flash the highlight to alert the user that we've moved
      setHighlightedLocation(coords)
      setTimeout(() => {
        setHighlightedLocation(null)
      }, 500)
    }

    map?.on('dragend', dragListener)
    map?.on('moveend', moveListener)
    return function cleanup() {
      map?.off('dragend', dragListener)
      map?.off('moveend', moveListener)
    }
  }, [map, setViewedNearbyCoords, setHighlightedLocation])

  useEffect(() => {
    if (typeof firstItemRef.current?.scrollIntoView === 'function') {
      firstItemRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
    if (finalNearbyCoords) {
      fetchNearby(finalNearbyCoords, radius)
      setLoading(true)
      const interval = setInterval(() => {
        fetchNearby(finalNearbyCoords, radius)
        setLoading(true)
      }, AUTO_REFRESH_INTERVAL)
      return function cleanup() {
        clearInterval(interval)
      }
    }
  }, [finalNearbyCoords, fetchNearby, radius])

  const onMouseEnter = useCallback(
    (location: Location) => {
      setHighlightedLocation(location)
      map && zoomToPlace(map, location)
    },
    [setHighlightedLocation, map, zoomToPlace]
  )
  const onMouseLeave = useCallback(() => {
    setHighlightedLocation(null)
  }, [setHighlightedLocation])

  // Determine whether the data we have is stale based on whether the coords match the URL
  // Sometimes Redux could have data from a previous load of the nearby view
  const staleData =
    finalNearbyCoords?.lat !== displayedCoords?.lat ||
    finalNearbyCoords?.lon !== displayedCoords?.lon

  const nearbyItemList =
    nearby?.map &&
    nearby
      ?.filter((n: any) => {
        return (!validLocations || validLocations?.includes(n.place.__typename)) && (n.place.__typename !== 'Stop' || n.place.stoptimesForPatterns.length > 0);
      })
      .map((n: any) => (
        <li
          className={
            (n.place.gtfsId ?? n.place.id) === entityId ? 'highlighted' : ''
          }
          key={n.place.id}
        >
          {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions */}
          <div
            className="nearby-view-card"
            onBlur={onMouseLeave}
            onFocus={() => onMouseEnter(n.place)}
            onMouseEnter={() => onMouseEnter(n.place)}
            onMouseLeave={onMouseLeave}
            /* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */
            tabIndex={0}
          >
            {getNearbyItem({ ...n.place, distance: n.distance }, handlePlanTripClick)}
          </div>
        </li>
      ))

  useEffect(() => {
    if (!staleData) {
      setLoading(false)
    }
  }, [nearby, staleData])

  const goBack = useCallback(
    () => setMainPanelContent(0),
    [setMainPanelContent]
  )

  const MainContainer = mobile ? MobileContainer : Scrollable

  return (
    <MainContainer className="nearby-view base-color-bg">
      <PageTitle
        title={intl.formatMessage({ id: 'components.NearbyView.header' })}
      />
      {mobile && (
        <MobileNavigationBar
          headerText={intl.formatMessage({
            id: 'components.NearbyView.header'
          })}
          onBackClicked={goBack}
        />
      )}
      {nearby && (
        <h3 style={{ opacity: 0, position: 'absolute' }}>
          <FormattedMessage
            id="components.NearbyView.nearbyListIntro"
            values={{ count: nearby.length }}
          />
        </h3>
      )}
      <NearbySidebarContainer
        className="base-color-bg"
        style={{ marginTop: mobile ? '50px' : 0 }}
      >
        {/* This is used to scroll to top */}
        <div aria-hidden ref={firstItemRef} />
        {loading && (
          <FloatingLoadingIndicator>
            <Loading extraSmall />
          </FloatingLoadingIndicator>
        )}
        {nearby &&
          !staleData &&
          (nearby.error ? (
            intl.formatMessage({ id: 'components.NearbyView.error' })
          ) : nearby.length > 0 ? (
            nearbyItemList
          ) : (
            <FormattedMessage id="components.NearbyView.nothingNearby" />
          ))}
      </NearbySidebarContainer>
      <VehiclePositionRetriever />
    </MainContainer>
  )
}

const mapStateToProps = (state: AppReduxState) => {
  const { config, location, transitIndex, ui } = state.otp
  const { map } = state.otp.config
  const { nearbyViewCoords } = ui
  const { nearby } = transitIndex
  const { entityId } = state.router.location.query
  const currentQuery = state.otp.currentQuery;
  const defaultLatLon =
    map?.initLat && map?.initLon ? { lat: map.initLat, lon: map.initLon } : null
  return {
    currentQuery,
    defaultLatLon,
    displayedCoords: nearby?.coords,
    entityId: entityId && decodeURIComponent(entityId),
    homeTimezone: config.homeTimezone,
    location: state.router.location.hash,
    nearby: nearby?.data,
    nearbyViewCoords,
    radius: config.nearbyView?.radius
  }
}

const mapDispatchToProps = {
  fetchNearby: apiActions.fetchNearby,
  setHighlightedLocation: uiActions.setHighlightedLocation,
  setLocation: mapActions.setLocation,
  setMainPanelContent: uiActions.setMainPanelContent,
  setViewedNearbyCoords: uiActions.setViewedNearbyCoords,
  viewNearby: uiActions.viewNearby,
  zoomToPlace: mapActions.zoomToPlace
}

export default connect(mapStateToProps, mapDispatchToProps)(NoiNearbyView)
