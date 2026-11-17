// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import React, { useEffect, useState, useRef } from 'react'
import { connect } from 'react-redux'
import { useMap, Popup, Source, Layer } from 'react-map-gl'
import * as mapActions from '@otp-react-redux/lib/actions/map'
import { SetLocationHandler } from '@otp-react-redux/lib/components/util/types'

import { addMarkerImages } from './marker-images'
import * as PopupIcons from '../../icons/popup'
import PoiPopup from './poi-popup'

type Props = {
  visible: boolean,
  setLocation: SetLocationHandler,
  url: string
}

interface PopupInfo {
  longitude: number
  latitude: number
  properties: any
}

// Used for the refresh interval.
let refreshInterface: NodeJS.Timeout | null = null

const TaxiOverlay = (props: Props) => {
  const map = useMap().default
  const { setLocation, url } = props

  // State for storing taxi details, GeoJSON data, and popup info.
  const [locations, setLocations] = useState<any[]>([])
  const [geoJsonData, setGeoJsonData] = useState<GeoJSON.FeatureCollection>({
    type: 'FeatureCollection',
    features: []
  })
  const [hoverInfo, setHoverInfo] = useState<PopupInfo | null>(null)
  const [stickyInfo, setStickyInfo] = useState<PopupInfo | null>(null)

  // A ref so event handlers always have the current sticky popup value.
  const stickyInfoRef = useRef<PopupInfo | null>(null)
  useEffect(() => {
    stickyInfoRef.current = stickyInfo
  }, [stickyInfo])

  // --- 1. Load the Taxi Icon ---

  useEffect(() => {
    if(!map) return
    const mapInstance = map.getMap()
    addMarkerImages(mapInstance)
  }, [map])

  // Track mount status to avoid state updates after unmount
  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

  // --- 2. Data Refresh: Fetch Taxi Data and Update GeoJSON State ---
  useEffect(() => {
    async function downloadLocations() {
      try {
        const response = await fetch(url)
        const json = await response.json()
        let taxis: any = {}

        json.data.forEach((taxi: any) => {
          const id = taxi.scode
          if (!taxis[id]) {
            taxis[id] = { ...taxi }
          }
          let taxiPoi = taxis[id]
          switch (taxi.tname) {
            case 'position':
              taxiPoi.coordinates = [
                parseFloat(taxi.mvalue.lon.replace(',', '.')),
                parseFloat(taxi.mvalue.lat.replace(',', '.'))
              ]
              break
            case 'state':
              taxiPoi.state = taxi.mvalue
              break
            default:
              console.debug('Unknown type: ' + taxi.tname)
              break
          }
        })

        let features: GeoJSON.Feature[] = []
        for (let id in taxis) {
          let taxi = taxis[id]
          if (taxi.coordinates) {
            // Only display available taxis
            //if (taxi.state === 'FREE' || taxi.state === 'AVAILABLE') {
              features.push({
                type: 'Feature',
                geometry: {
                  type: 'Point',
                  coordinates: taxi.coordinates
                },
                properties: {
                  Name: taxi.sname,
                  Address: taxi.state,
                  Phone: taxi.scode,
                  Region: taxi.prlineage,
                  State: taxi.state
                }
              })
            //}
          }
        }

        if (!isMountedRef.current) return
        setGeoJsonData({
          type: 'FeatureCollection',
          features: features
        })
        setLocations(taxis)
      } catch (err) {
        console.error(err)
      }
    }

    if (url) {
      downloadLocations()
    }

    if (refreshInterface) {
      clearInterval(refreshInterface)
    }
    refreshInterface = setInterval(() => {
      downloadLocations()
    }, 5000)

    return () => {
      if (refreshInterface) {
        clearInterval(refreshInterface)
      }
    }
  }, [url])

  // --- 3. Attach Event Listeners for Hover & Click Popups ---
  // Once the GeoJSON data is available and the layer is rendered by React GL,
  // we add event listeners on the map instance.
  useEffect(() => {
    if(!map) return
    const mapInstance = map.getMap()
    // If the layer doesn't exist yet, skip attaching listeners.
    if (!mapInstance || !geoJsonData || !mapInstance.getLayer('taxi_noi')) return

    const onTaxiMouseEnter = (e: any) => {
      mapInstance.getCanvas().style.cursor = 'pointer'
      if (!isMountedRef.current) return
      if (!stickyInfoRef.current && e.features && e.features.length) {
        const feature = e.features[0]
        const coordinates = feature.geometry.coordinates
        if (!isMountedRef.current) return
        setHoverInfo({
          longitude: coordinates[0],
          latitude: coordinates[1],
          properties: feature.properties
        })
      }
    }

    const onTaxiMouseLeave = () => {
      mapInstance.getCanvas().style.cursor = ''
      if (!isMountedRef.current) return
      setHoverInfo(null)
    }

    const onTaxiClick = (e: any) => {
      if (!isMountedRef.current) return
      setHoverInfo(null)
      if (e.features && e.features.length) {
        const feature = e.features[0]
        const coordinates = feature.geometry.coordinates
        if (!isMountedRef.current) return
        setStickyInfo({
          longitude: coordinates[0],
          latitude: coordinates[1],
          properties: feature.properties
        })
      }
    }

    mapInstance.on('mouseenter', 'taxi_noi', onTaxiMouseEnter)
    mapInstance.on('mouseleave', 'taxi_noi', onTaxiMouseLeave)
    mapInstance.on('click', 'taxi_noi', onTaxiClick)

    return () => {
      mapInstance.off('mouseenter', 'taxi_noi', onTaxiMouseEnter)
      mapInstance.off('mouseleave', 'taxi_noi', onTaxiMouseLeave)
      mapInstance.off('click', 'taxi_noi', onTaxiClick)
    }
  }, [map, geoJsonData])

  return (
    <>
      {/* Render the source and layer using react-map-gl components */}
      {geoJsonData && (
        <Source id="taxi_noi" type="geojson" data={geoJsonData}>
          <Layer
            id="taxi_noi"
            type="symbol"
            minzoom={12}
            layout={{
              'text-anchor': 'bottom',
              'text-size': 12,
              'text-field': 'Taxi',
              // Pin marker: the tip sits on the taxi position. Grey: no realtime state in the design for taxis.
              'icon-anchor': 'bottom',
              'icon-image': 'StopTaxi-grey',
              'icon-allow-overlap': false,
              'text-allow-overlap': false,
              'text-optional': true,
              'icon-size': 1
            }}
            paint={{
              'icon-opacity': ['case', ["!=", ['get', 'State'], 'OCCUPIED'], 1.0, 0.3],
              'text-opacity': ['case', ["!=", ['get', 'State'], 'OCCUPIED'], 1.0, 0.3]
            }}
          />
        </Source>
      )}

      {stickyInfo && (
        <Popup
          // Same placement as the other POI popups (custom-otp-layers.tsx).
          anchor="bottom"
          className="poi-popup-container"
          closeButton={false}
          latitude={stickyInfo.latitude}
          longitude={stickyInfo.longitude}
          maxWidth="none"
          offset={40}
          onClose={() => setStickyInfo(null)}
        >
          <PoiPopup
            icon={<PopupIcons.Cab />}
            place={{ lon: stickyInfo.longitude, lat: stickyInfo.latitude, name: stickyInfo.properties.Name, properties: stickyInfo.properties }}
            title={stickyInfo.properties.Name}
          >
            State: {stickyInfo.properties.State}
            <div className="poi-popup__info">
              Region: {stickyInfo.properties.Region}
            </div>
          </PoiPopup>
        </Popup>
      )}
    </>
  )
}

const mapDispatchToProps = {
  setLocation: mapActions.setLocation
}

export default connect(null, mapDispatchToProps)(TaxiOverlay)
