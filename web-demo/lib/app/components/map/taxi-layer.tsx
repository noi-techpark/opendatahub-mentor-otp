// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import React, { useEffect, useState, useRef } from 'react'
import { connect } from 'react-redux'
import { useMap, Popup, Source, Layer } from 'react-map-gl'
import * as mapActions from '@otp-react-redux/lib/actions/map'
import { SetLocationHandler } from '@otp-react-redux/lib/components/util/types'

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
  // We define an SVG taxi icon as a data URL and add it to the map style.
  const taxiIconData =
    `data:image/svg+xml;base64,PHN2ZwogICAgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIgogICAgdmlld0JveD0iMCAwIDUxMiA1MTIiCiAgICBoZWlnaHQ9IjEwMCUiCiAgICB3aWR0aD0iMTAwJSIKICA+CiAgICA8cGF0aCBkPSJNNDk5Ljk5MSAxNjhoLTU0LjgxNWwtNy44NTQtMjAuOTQ0Yy05LjE5Mi0yNC41MTMtMjUuNDI1LTQ1LjM1MS00Ni45NDItNjAuMjYzUzM0My42NTEgNjQgMzE3LjQ3MiA2NEgxOTQuNTI4Yy0yNi4xOCAwLTUxLjM5MSA3Ljg4Mi03Mi45MDggMjIuNzkzLTIxLjUxOCAxNC45MTItMzcuNzUgMzUuNzUtNDYuOTQyIDYwLjI2M0w2Ni44MjQgMTY4SDEyLjAwOWMtOC4xOTEgMC0xMy45NzQgOC4wMjQtMTEuMzg0IDE1Ljc5NWw4IDI0QTEyIDEyIDAgMCAwIDIwLjAwOSAyMTZoMjguODE1bC0uMDUyLjE0QzI5LjIyMiAyMjcuMDkzIDE2IDI0Ny45OTcgMTYgMjcydjQ4YzAgMTYuMjI1IDYuMDQ5IDMxLjAyOSAxNiA0Mi4zMDlWNDI0YzAgMTMuMjU1IDEwLjc0NSAyNCAyNCAyNGg0OGMxMy4yNTUgMCAyNC0xMC43NDUgMjQtMjR2LTQwaDI1NnY0MGMwIDEzLjI1NSAxMC43NDUgMjQgMjQgMjRoNDhjMTMuMjU1IDAgMjQtMTAuNzQ1IDI0LTI0di02MS42OTFjOS45NTEtMTEuMjgxIDE2LTI2LjA4NSAxNi00Mi4zMDl2LTQ4YzAtMjQuMDAzLTEzLjIyMi00NC45MDctMzIuNzcyLTU1Ljg2bC0uMDUyLS4xNGgyOC44MTVhMTIgMTIgMCAwIDAgMTEuMzg0LTguMjA1bDgtMjRjMi41OS03Ljc3MS0zLjE5My0xNS43OTUtMTEuMzg0LTE1Ljc5NXptLTM2NS4zODggMS41MjhDMTQzLjkxOCAxNDQuNjg5IDE2OCAxMjggMTk0LjUyOCAxMjhoMTIyLjk0NGMyNi41MjggMCA1MC42MSAxNi42ODkgNTkuOTI1IDQxLjUyOEwzOTEuODI0IDIwOEgxMjAuMTc2bDE0LjQyNy0zOC40NzJ6TTg4IDMyOGMtMTcuNjczIDAtMzItMTQuMzI3LTMyLTMyIDAtMTcuNjczIDE0LjMyNy0zMiAzMi0zMnM0OCAzMC4zMjcgNDggNDgtMzAuMzI3IDE2LTQ4IDE2em0zMzYgMGMtMTcuNjczIDAtNDggMS42NzMtNDgtMTYgMC0xNy42NzMgMzAuMzI3LTQ4IDQ4LTQ4czMyIDE0LjMyNyAzMiAzMmMwIDE3LjY3My0xNC4zMjcgMzItMzIgMzJ6IiAvPgogIDwvc3ZnPg==`

  useEffect(() => {
    if(!map) return
    const mapInstance = map.getMap()
    const carIcon = new Image()
    carIcon.onload = function () {
      
      carIcon.width = 150
      carIcon.height = 150

      if (!mapInstance.hasImage('taxi')) {
        mapInstance.addImage('taxi', carIcon)
      }
    }
    carIcon.src = taxiIconData
  }, [map, taxiIconData])

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
    if (!mapInstance || !geoJsonData || !mapInstance.getLayer('parking_noi')) return

    const onTaxiMouseEnter = (e: any) => {
      mapInstance.getCanvas().style.cursor = 'pointer'
      if (!stickyInfoRef.current && e.features && e.features.length) {
        const feature = e.features[0]
        const coordinates = feature.geometry.coordinates
        setHoverInfo({
          longitude: coordinates[0],
          latitude: coordinates[1],
          properties: feature.properties
        })
      }
    }

    const onTaxiMouseLeave = () => {
      mapInstance.getCanvas().style.cursor = ''
      setHoverInfo(null)
    }

    const onTaxiClick = (e: any) => {
      setHoverInfo(null)
      if (e.features && e.features.length) {
        const feature = e.features[0]
        const coordinates = feature.geometry.coordinates
        setStickyInfo({
          longitude: coordinates[0],
          latitude: coordinates[1],
          properties: feature.properties
        })
      }
    }

    mapInstance.on('mouseenter', 'parking_noi', onTaxiMouseEnter)
    mapInstance.on('mouseleave', 'parking_noi', onTaxiMouseLeave)
    mapInstance.on('click', 'parking_noi', onTaxiClick)

    return () => {
      mapInstance.off('mouseenter', 'parking_noi', onTaxiMouseEnter)
      mapInstance.off('mouseleave', 'parking_noi', onTaxiMouseLeave)
      mapInstance.off('click', 'parking_noi', onTaxiClick)
    }
  }, [map, geoJsonData])

  return (
    <>
      {/* Render the source and layer using react-map-gl components */}
      {geoJsonData && (
        <Source id="parking_noi" type="geojson" data={geoJsonData}>
          <Layer
            id="parking_noi"
            type="symbol"
            minzoom={12}
            layout={{
              'text-anchor': 'bottom',
              'text-size': 12,
              'text-field': 'Taxi',
              'icon-anchor': 'top',
              'icon-image': 'taxi',
              'icon-size': 0.1
            }}
            paint={{
              'icon-opacity': ['case', ["!=", ['get', 'State'], 'OCCUPIED'], 1.0, 0.3],
              'text-opacity': ['case', ["!=", ['get', 'State'], 'OCCUPIED'], 1.0, 0.3]
            }}
          />
        </Source>
      )}

      {/* Render popups using react-map-gl's Popup */}
      {hoverInfo && (
        <Popup
          longitude={hoverInfo.longitude}
          latitude={hoverInfo.latitude}
          closeButton={false}
          offsetTop={-10}
          anchor="top"
        >
          <div>
            <strong>{hoverInfo.properties.Name}</strong>
            <br />
            State: {hoverInfo.properties.State}
            <br />
            Region: {hoverInfo.properties.Region}
          </div>
        </Popup>
      )}
      {stickyInfo && (
        <Popup
          longitude={stickyInfo.longitude}
          latitude={stickyInfo.latitude}
          closeButton={true}
          onClose={() => setStickyInfo(null)}
          offsetTop={-10}
          anchor="top"
        >
          <div>
            <strong>{stickyInfo.properties.Name}</strong>
            <br />
            State: {stickyInfo.properties.State}
            <br />
            Region: {stickyInfo.properties.Region}
            <br />
            <em>Click outside to dismiss.</em>
          </div>
        </Popup>
      )}
    </>
  )
}

const mapDispatchToProps = {
  setLocation: mapActions.setLocation
}

export default connect(null, mapDispatchToProps)(TaxiOverlay)
