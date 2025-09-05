// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import React, { useEffect, useState, useRef } from 'react'
import { connect } from 'react-redux'
import ReactDOMServer from 'react-dom/server'

import { useMap, Popup, Source, Layer } from 'react-map-gl'
import * as mapActions from '@otp-react-redux/lib/actions/map'
import { SetLocationHandler } from '@otp-react-redux/lib/components/util/types'
import NoiFromToPicker from '../viewers/nearby/noi-from-to-picker'
import Charger from '../../icons/Charger'

type Props = {
  visible: boolean,
  setLocation: SetLocationHandler,
  url: string
}

interface PopupInfo {
  lon: number
  lat: number
  properties: any
}

// Used for the refresh interval.
let refreshInterface: NodeJS.Timeout | null = null

const ChargerOverlay = (props: Props) => {
  const map = useMap().default
  const { setLocation, url } = props

  // Track mount status to prevent state updates after unmount
  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

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
  // We define an SVG taxi icon as a data URL and add it to the map style.
  
  useEffect(() => {
    if(!map) return
    const mapInstance = map.getMap()
    if (!mapInstance) return

    const chargerSvgString = ReactDOMServer.renderToStaticMarkup(
        <Charger />
    )
    const chargerDataUrl = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
        chargerSvgString
    )}`
    const chargerIcon = new Image()

    chargerIcon.width = 24  // Set appropriate size
    chargerIcon.height = 20 // Set appropriate size

    chargerIcon.onload = () => {
        if (!mapInstance.hasImage('charger-icon')) {
        mapInstance.addImage('charger-icon', chargerIcon, {sdf: true})
        }
    }
    chargerIcon.src = chargerDataUrl
  }, [map])

  // --- 2. Data Refresh: Fetch Taxi Data and Update GeoJSON State ---
  useEffect(() => {
    async function downloadLocations() {
      try {
        const response = await fetch(url)
        const json = await response.json()
        let chargers: any = {}

        json.data.stations.forEach((station: any) => {
            let id = station.station_id
            chargers[id] = {
                coordinates: [
                    station.lon,
                    station.lat
                ],
                ...station
            }

        })

        let features: GeoJSON.Feature[] = []
        for (let id in chargers) {
          let station = chargers[id]
          if (station.coordinates) {
            // Only display available stations
            if (station.free > 0) {
              features.push({
                type: 'Feature',
                geometry: {
                  type: 'Point',
                  coordinates: station.coordinates
                },
                properties: {
                  ...station
                }
              })
            }
          }
        }

        if (!isMountedRef.current) return
        setGeoJsonData({
          type: 'FeatureCollection',
          features: features
        })
        setLocations(chargers)
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
    if (!mapInstance || !geoJsonData || !mapInstance.getLayer('charger_noi')) return

    const onChargerMouseEnter = (e: any) => {
      mapInstance.getCanvas().style.cursor = 'pointer'
      if (!isMountedRef.current) return
      if (!stickyInfoRef.current && e.features && e.features.length) {
        const feature = e.features[0]
        const coordinates = feature.geometry.coordinates
        if(feature.properties.plugs) {
            feature.properties.plugs = JSON.parse(feature.properties.plugs);
        }
        if(feature.properties.plugsTypes) {
            feature.properties.plugsTypes = JSON.parse(feature.properties.plugsTypes);
        }

        if (!isMountedRef.current) return
        setHoverInfo({
          lon: coordinates[0],
          lat: coordinates[1],
          properties: feature.properties
        })
      }
    }

    const onChargerMouseLeave = () => {
      mapInstance.getCanvas().style.cursor = ''
      if (!isMountedRef.current) return
      setHoverInfo(null)
    }

    const onChargerClick = (e: any) => {
      if (!isMountedRef.current) return
      setHoverInfo(null)

      if (e.features && e.features.length) {
        const feature = e.features[0]
        const coordinates = feature.geometry.coordinates
        if(feature.properties.plugs) {
            feature.properties.plugs = JSON.parse(feature.properties.plugs);
        }
        if(feature.properties.plugsTypes) {
            feature.properties.plugsTypes = JSON.parse(feature.properties.plugsTypes);
        }
        if (!isMountedRef.current) return
        setStickyInfo({
          lon: coordinates[0],
          lat: coordinates[1],
          properties: feature.properties
        })
      }
    }

    mapInstance.on('mouseenter', 'charger_noi', onChargerMouseEnter)
    mapInstance.on('mouseleave', 'charger_noi', onChargerMouseLeave)
    mapInstance.on('click', 'charger_noi', onChargerClick)

    return () => {
      mapInstance.off('mouseenter', 'charger_noi', onChargerMouseEnter)
      mapInstance.off('mouseleave', 'charger_noi', onChargerMouseLeave)
      mapInstance.off('click', 'charger_noi', onChargerClick)
    }
  }, [map, geoJsonData])

  return (
    <>
      {/* Render the source and layer using react-map-gl components */}
      {geoJsonData && (
        <Source id="charger_noi" type="geojson" data={geoJsonData}>
          <Layer
            id="charger_noi"
            type="symbol"
            minzoom={14}
            layout={{
              'text-anchor': 'bottom',
              'text-size': 12,
              //'text-field': ['get', 'name'],
              'icon-anchor': 'top',
              'icon-image': 'charger-icon',
              'icon-size': 1
            }}
            paint={{
              'icon-opacity': ['case', [">", ['get', 'free'], 0], 1.0, 0.3],
              'text-opacity': ['case', [">", ['get', 'free'], 0], 1.0, 0.3]
            }}
          />
        </Source>
      )}

      {/* Render popups using react-map-gl's Popup */}
      {hoverInfo && (
        <></>/*<Popup
          longitude={hoverInfo.lon}
          latitude={hoverInfo.lat}
          closeButton={false}
          offsetTop={-10}
          anchor="top"
        >
          <div className="otp-ui-mapOverlayPopup">
            <div className="otp-ui-mapOverlayPopup__popupHeader">
            <Charger width={24} height={20} />
            &nbsp;E-Charger
            </div>

            <div className="otp-ui-mapOverlayPopup__popupTitle">
            {hoverInfo.properties.name}
            </div>

            <div>
            Provider: {hoverInfo.properties.provider}
            </div>

            <div className="otp-ui-mapOverlayPopup__popupAvailableInfo">
            <div className="otp-ui-mapOverlayPopup__popupAvailableInfoValue">
                {hoverInfo.properties.free}
            </div>
            </div>
            <div className="otp-ui-mapOverlayPopup__popupAvailableSlots">
            {hoverInfo.properties.plugs.map((plug, key) => {
                const ava = plug.available ? "bg-success" : "bg-danger";

                plug.maxPower = Math.round(plug.maxPower);

                return (
                <div className="otp-ui-mapOverlayPopup__popupAvailableSlotItem">
                    <div>
                    <span className={ava}></span>
                    <strong>
                        {key + 1}
                    </strong>
                    <br />
                    <br />
                    {plug.maxPower}W | {plug.minCurrent}-
                    {plug.maxCurrent}A
                    <br />
                    <br />
                    <small>
                        Type {plug.outletTypeCode}
                    </small>
                    </div>
                </div>
                );
            })}
            </div>

            <div className="otp-ui-mapOverlayPopup__popupRow">
            <NoiFromToPicker
                place={hoverInfo}
            />
            </div>
        </div>
        </Popup>*/
      )}
      {stickyInfo && (
        <div >
        <Popup
          maxWidth="none"
          longitude={stickyInfo.lon}
          latitude={stickyInfo.lat}
          closeButton={true}
          onClose={() => setStickyInfo(null)}
          offsetTop={-10}
          anchor="top"
        >
          <div className="otp-ui-mapOverlayPopup">
            <div className="otp-ui-mapOverlayPopup__popupHeader">
            <Charger width={24} height={20} />
            &nbsp;E-Charger
            </div>

            <div className="otp-ui-mapOverlayPopup__popupTitle">
            {stickyInfo.properties.name}
            </div>

            <div>
            Provider: {stickyInfo.properties.provider}
            </div>

            <div className="otp-ui-mapOverlayPopup__popupAvailableInfo">
            <div className="otp-ui-mapOverlayPopup__popupAvailableInfoValue">
                {stickyInfo.properties.free}
            </div>
            </div>
            <div className="otp-ui-mapOverlayPopup__popupAvailableSlots">
            {stickyInfo.properties.plugs.map((plug, key) => {
                const ava = plug.available ? "bg-success" : "bg-danger";

                plug.maxPower = Math.round(plug.maxPower);

                return (
                <div className="otp-ui-mapOverlayPopup__popupAvailableSlotItem">
                    <div>
                    <span className={ava}></span>
                    <strong>
                        {key + 1}
                    </strong>
                    <br />
                    <br />
                    {plug.maxPower}W | {plug.minCurrent}-
                    {plug.maxCurrent}A
                    <br />
                    <br />
                    <small>
                        Type {plug.outletTypeCode}
                    </small>
                    </div>
                </div>
                );
            })}
            </div>

            <div className="otp-ui-mapOverlayPopup__popupRow">
            <NoiFromToPicker
                place={stickyInfo}
            />
            </div>
        </div>
        </Popup>
        </div>
      )}
    </>
  )
}

const mapDispatchToProps = {
  setLocation: mapActions.setLocation
}

export default connect(null, mapDispatchToProps)(ChargerOverlay)
