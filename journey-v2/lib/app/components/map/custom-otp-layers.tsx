// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import React, { useState, useEffect, useRef } from 'react'
import { Layer, Popup, useMap } from 'react-map-gl'
import { Label as BsLabel } from 'react-bootstrap'
import ReactDOMServer from 'react-dom/server'
import { FormattedMessage, injectIntl } from 'react-intl'
import { ClassicModeIcon } from '@opentripplanner/icons'
import { Bicycle, Parking } from '@styled-icons/fa-solid'
import { Rss } from '@styled-icons/fa-solid/Rss'
import { Wheelchair } from '@styled-icons/fa-solid/Wheelchair'
import { setViewedStop } from '@otp-react-redux/lib/actions/ui'
import { setLocation } from '@otp-react-redux/lib/actions/map'
import { IconWithText } from '@otp-react-redux/lib/components/util/styledIcon'
import styled from 'styled-components'
import NoiFromToPicker from '../viewers/nearby/noi-from-to-picker'
import { connect } from 'react-redux'
import { Button } from '@opentripplanner/endpoints-overlay/lib/styled'
import { addMarkerImages } from './marker-images'



const PulsingRss = styled(Rss)`
  animation: pulse-opacity 2s ease-in-out infinite;
  transform: scaleX(-1);
`


// --- Default configuration for layers ---
const LAYER_CONFIG = {
  stops: {
    type: 'symbol',
    filter: [
      'all',
      ['has', 'routes'],
      [
        'case',
        ['==', ['get', 'routes'], '[]'],
        false,
        ['>', ['length', ['get', 'routes']], 0]
      ]
    ],
    layout: {
      // Marker image is `<Icon>-<state>`: green when the stop has realtime data, grey otherwise.
      'icon-image': [
        'concat',
        ['match', ['get', 'type'], 'BUS', 'StopBus', 'TRAM', 'StopTram', 'StopTrain'],
        '-',
        ['case', ['==', ['get', 'realTimeData'], true], 'green', 'grey']
      ],
      // Pin markers: the tip sits on the stop coordinate.
      'icon-anchor': 'bottom',
      'icon-size': 1,
      'icon-allow-overlap': true,
      'text-allow-overlap': true,
      'text-optional': true,
      // Only show stop names once zoomed in (>=18) to reduce clutter.
      'text-field':  ["step", ["zoom"], ["get", "name"], 16, "", 17, ["get", "platform"]],
      'text-offset': [0, 1.0],
      'text-size': 12,
      'text-anchor': 'top'
    },
    minzoom: 16,
    maxzoom: 20,
    popupRenderer: (properties, hoverInfo, setViewedStop, setLocation) => {
      return (
        <div className="otp-ui-mapOverlayPopup" style={{width: '300px'}}>
          <div className="otp-ui-mapOverlayPopup__popupHeader">
            {properties.type.includes('BUS') && <IconWithText icon={<ClassicModeIcon mode="bus" />} />}
            {properties.type.includes('RAIL') && <IconWithText icon={<ClassicModeIcon mode="rail" />} />}
            &nbsp;
            Stop
            {properties.realTimeData && (
              <PulsingRss width="16px" style={{ position: 'absolute', right: '5px' }} />
            )}
          </div>
          <div className="otp-ui-mapOverlayPopup__popupTitle" style={{padding: '5px 0'}}>
            <Button onClick={() => { setViewedStop({...properties, stopId: properties.gtfsId}, "nearby");}}>{properties.name}</Button>
          </div>
          {properties.platform && <div>
            <strong>Platform:</strong> {properties.platform}
          </div>}
          <div className="otp-ui-mapOverlayPopup__popupRow" style={{padding: '10px 0 0 0'}}>
            <NoiFromToPicker place={hoverInfo} />
          </div>
        </div>
      )
    }
  },
  // Not integrated yet, for flex-trip, zones
  areaStops: {
    // Flex zones – using a circle to indicate an area
    type: 'circle',
    paint: {
      'circle-radius': 8,
      'circle-color': '#FFA500',
      'circle-opacity': 0.8
    },
    minzoom: 14,
    maxzoom: 20
  },
  stations: {
    type: 'symbol',
    filter: [
      'all',
      ['has', 'routes'],
      [
        'case',
        ['==', ['get', 'routes'], '[]'],
        false,
        ['>', ['length', ['get', 'routes']], 0]
      ],
      [">=", ["zoom"],
        ["match", ["get", "type"],
            "BUS",  // rank
            15, // minimum zoom level
            "RAIL",  // etc.
            1,
            "BUS,RAIL",
            1,
            "RAIL,BUS",
            1,
            15  // fallback for ranks > 4
        ]
      ]
    ],
    layout: {
      // Bus-only stations use the stop pin, everything else the train station circle.
      'icon-image': [
        'concat',
        ['match', ['get', 'type'], 'BUS', 'StopBus', 'StationTrain'],
        '-',
        ['case', ['==', ['get', 'realTimeData'], true], 'green', 'grey']
      ],
      // The pin tip sits on the coordinate, the circle is centred on it.
      'icon-anchor': ['match', ['get', 'type'], 'BUS', 'bottom', 'center'],
      'icon-size': 1,
      'icon-allow-overlap': true,
      'text-optional': true,
      'text-field':  ["step", ["zoom"], [
        'match',
        ['get', 'type'],
        'BUS', '',
        'RAIL,BUS', ["get", "name"],
        "BUS,RAIL", ["get", "name"],
        ""
      ], 16, ["get", "platform"]],
      'text-offset': [0, 1.0],
      'text-size': 12,
      'text-anchor': 'top',
      'symbol-sort-key':  [
        'match',
        ['get', 'type'],
        'BUS', 10,
        'RAIL,BUS', 1,
        'BUS,RAIL', 1,
        'RAIL', 5,
        10
        ],
    },
    minzoom: 1,
    maxzoom: 16,
    popupRenderer: (properties, hoverInfo) => {
      return (
        <div className="otp-ui-mapOverlayPopup" style={{width: '300px'}}>
          <div className="otp-ui-mapOverlayPopup__popupHeader">
            {properties.type.includes('BUS') && <IconWithText icon={<ClassicModeIcon mode="bus" />} />}
            {properties.type.includes('RAIL') && <IconWithText icon={<ClassicModeIcon mode="rail" />} />}
            &nbsp;
            Station
            {properties.realTimeData && (
              <PulsingRss width="16px" style={{ position: 'absolute', right: '5px' }} />
            )}
          </div>
          <div className="otp-ui-mapOverlayPopup__popupTitle" style={{padding: '5px 0'}}>
            {properties.name}
          </div>
          <div className="otp-ui-mapOverlayPopup__popupRow" style={{padding: '10px 0 0 0'}}>
            <NoiFromToPicker place={hoverInfo} />
          </div>
        </div>
      )
    }
  },
  // Free-floating bike, none yet, would be implemented here
  citybikes: {
    type: 'symbol',
    layout: {
      'icon-image': 'bicycle-icon',
      'icon-size': 0.15,
      'icon-allow-overlap': false,
      'text-field': ['get', 'name'],
      'text-offset': [0, 1.2],
      'text-size': 10,
      'text-anchor': 'top'
    },
    minzoom: 14,
    maxzoom: 20
  },
  // Free-floating vehicles, none yet, to be implemented
  rentalVehicles: {
    type: 'symbol',
    layout: {
      'icon-image': 'scooter-icon', 
      'icon-size': 0.1,
      'icon-allow-overlap': true,
      'text-field': ['get', 'name'],
      'text-offset': [0, 1],
      'text-size': 10,
      'text-anchor': 'top'
    },
    minzoom: 1,
    maxzoom: 20
  },
  rentalStations: {
    type: 'symbol',
    filter: ['!=', 'formFactors', ''],
    layout: {
      'icon-image': [
        'concat',
        [
          'match',
          ['get', 'formFactors'],
          'BICYCLE', 'RentalBike',
          'CAR', 'RentalCar',
          'BICYCLE,CAR', 'RentalCar',
          'CAR,BICYCLE', 'RentalCar',
          /* default */ 'RentalBike'
        ],
        '-',
        [
          'case',
          // No live count -> grey
          ['!', ['has', 'vehiclesAvailable']], 'grey',
          // Not operative or none left -> red
          ['==', ['get', 'operative'], false], 'red',
          ['<=', ['get', 'vehiclesAvailable'], 0], 'red',
          // 1-2 left -> amber, more than 2 -> green
          ['>', ['get', 'vehiclesAvailable'], 2], 'green',
          'amber'
        ]
      ],
      // Circle marker centred on the station; the label sits below it.
      'icon-anchor': 'center',
      'icon-size': 1,
      'icon-allow-overlap': false,
      'text-optional': true,
      'text-field':  ["step", ["zoom"], "", 17, ["get", "name"]],
      'text-offset': [0, 2],
      'text-size': 12,
      'text-anchor': 'top'
    },
    minzoom: 10,
    maxzoom: 20,
        popupRenderer: (properties, hoverInfo) => {
      let formFactors = properties.formFactors.split(",");
      return (
        <div className="otp-ui-mapOverlayPopup" style={{width: '300px'}}>
          <div className="otp-ui-mapOverlayPopup__popupHeader">
            {formFactors.includes('BICYCLE') && <IconWithText icon={<ClassicModeIcon mode="bicycle" />} />}
            {formFactors.includes('CAR') && <IconWithText icon={<ClassicModeIcon mode="car" />} />}
            &nbsp;
            Rental Station
            {properties.operative && (
              <PulsingRss width="16px" style={{ position: 'absolute', right: '5px' }} />
            )}
          </div>
          <div className="otp-ui-mapOverlayPopup__popupTitle" style={{padding: '5px 0'}}>
            {properties.name}
          </div>
          <div className="otp-ui-mapOverlayPopup__popupRow">
            <div>
              {formFactors.includes('BICYCLE') && !formFactors.includes('CAR') &&
                `${properties.vehiclesAvailable} bikes available`
              }
              {formFactors.includes('CAR') && !formFactors.includes('BICYCLE') &&
                `${properties.vehiclesAvailable} cars available`
              }
              {formFactors.includes('BICYCLE') && formFactors.includes('CAR') &&
                `${properties.vehiclesAvailable} vehicles available`
              }
              {!formFactors.includes('BICYCLE') && !formFactors.includes('CAR') &&
                `${properties.vehiclesAvailable} vehicles available`
              }
            </div>
          </div>
          <div className="otp-ui-mapOverlayPopup__popupRow" style={{padding: '10px 0 0 0'}}>
            <NoiFromToPicker place={hoverInfo} />
          </div>
        </div>
      );
    }
  },
  vehicleParking: {
    type: 'symbol',
    layout: {
    // Grey until live occupancy is verified: capacity alone says nothing about free places.
    'icon-image': [
      'concat',
      [
        'case',
        [
          'all',
          ['==', ['get', 'bicyclePlaces'], true],
          ['!=', ['get', 'carPlaces'], true]
        ],
        'ParkingBike',
        'ParkingCar'
      ],
      '-grey'
    ],
    // Circle marker centred on the parking; the label sits below it.
    'icon-anchor': 'center',
    'icon-size': 1,
    'icon-allow-overlap': false,
    'text-optional': true,
    'text-field':  ["step", ["zoom"], "", 17, ["get", "name"]],
    'text-offset': [0, 2],
    'text-size': 12,
    'text-anchor': 'top'
    },
    minzoom: 14,
    maxzoom: 20,
    popupRenderer: (properties, hoverInfo) => {
        const isBikeParking = properties.bicyclePlaces && !properties.carPlaces;
        const title = isBikeParking ? 'Bike parking' : 'Parking';
        const name = properties.name && properties.name.trim() ? properties.name.trim() : title;
        return (
            <div className="otp-ui-mapOverlayPopup" style={{minWidth: '200px'}}>
                <div className="otp-ui-mapOverlayPopup__popupHeader">
                    <IconWithText icon={isBikeParking ? <Bicycle /> : <Parking />} />
                    &nbsp;{title}
                    {properties.realTimeData && (
                      <PulsingRss width="16px" style={{ position: 'absolute', right: '5px' }} />
                    )}
                </div>
                <div className="otp-ui-mapOverlayPopup__popupTitle" style={{padding: '5px 0'}}>
                    {name}
                </div>
                <div style={{padding: '5px 0'}}>
                    {properties.wheelchairAccessibleCarPlaces && <BsLabel bsStyle="primary">
                      <IconWithText Icon={Wheelchair}>
                        <FormattedMessage id="components.TripViewer.accessible" />
                      </IconWithText>
                    </BsLabel>}
                  <div> { properties.carPlaces &&
                      <IconWithText icon={<ClassicModeIcon mode="car" />}>
                        {properties.realTimeData && properties['availability.carPlaces'] !== undefined
                          ? `${properties['availability.carPlaces']} / ${properties['capacity.carPlaces']}`
                          : properties['capacity.carPlaces']}
                      </IconWithText>
                    }
                  </div>
                  <div>
                    { properties.bicyclePlaces &&
                    <IconWithText icon={<ClassicModeIcon mode="bicycle" />}>
                      {properties.realTimeData && properties['availability.bicyclePlaces'] !== undefined
                        ? `${properties['availability.bicyclePlaces']} / ${properties['capacity.bicyclePlaces']}`
                        : properties['capacity.bicyclePlaces']}
                    </IconWithText>
                    }
                  </div>
                </div>
                <div className="otp-ui-mapOverlayPopup__popupRow" style={{padding: '10px 0 0 0'}}>
                    <NoiFromToPicker place={hoverInfo} />
                </div>
            </div>
        );
      }
  }
}

const OTPVectorLayer = ({ sourceLayerName, layerStyle = {}, name, setViewedStop, setLocation }) => {
  const map = useMap().default
  const [hoverInfo, setHoverInfo] = useState(null)
  const [stickyInfo, setStickyInfo] = useState(null)
  // For stops, track parentStation for highlighting.
  const [highlightParentStation, setHighlightParentStation] = useState(null)
  const stickyInfoRef = useRef(null)
  useEffect(() => {
    stickyInfoRef.current = stickyInfo
  }, [stickyInfo])

  // Merge default config with any overrides and ensure required keys.
  const defaultConfig = LAYER_CONFIG[sourceLayerName] || {}
  const mergedConfig = {
    source: 'otp-source',
    name: name || `otp-${sourceLayerName}`,
    visible: true,
    key: sourceLayerName,
    'source-layer': sourceLayerName,
    ...defaultConfig,
    ...layerStyle
  }

  // --- Load custom icons for stops (if needed) ---
  useEffect(() => {
    if (!map) return
    const mapInstance = map.getMap()
    if (!mapInstance) return
    addMarkerImages(mapInstance)
    // Micromobility icon
    if (!mapInstance.hasImage('scooter-icon')) {
      const scooterSvgString = ReactDOMServer.renderToStaticMarkup(
        <ClassicModeIcon mode="scooter" />
      )
      const scooterDataUrl = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
        scooterSvgString
      )}`
      const scooterIcon = new Image()
      
      scooterIcon.width = 150  // Set appropriate size
      scooterIcon.height = 150 // Set appropriate size
      scooterIcon.onload = () => {
        if (!mapInstance.hasImage('scooter-icon')) {
          mapInstance.addImage('scooter-icon', scooterIcon)
        }
      }
      scooterIcon.src = scooterDataUrl
    }
    // Bicycle icon
    if (!mapInstance.hasImage('bicycle-icon')) {
      const bicycleSvgString = ReactDOMServer.renderToStaticMarkup(
        <ClassicModeIcon mode="bicycle" />
      )
      const bicycleDataUrl = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
        bicycleSvgString
      )}`
      const bicycleIcon = new Image()

      bicycleIcon.width = 245  // Set appropriate size
      bicycleIcon.height = 150 // Set appropriate size

      bicycleIcon.onload = () => {
        if (!mapInstance.hasImage('bicycle-icon')) {
          mapInstance.addImage('bicycle-icon', bicycleIcon)
        }
      }
      bicycleIcon.src = bicycleDataUrl
    }
  }, [map, sourceLayerName])

  // Track mount status to avoid state updates on unmounted component
  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

  // --- Attach event listeners (hover, click) ---
  useEffect(() => {
    if (!map) return
    const mapInstance = map.getMap()
    if (!mapInstance) return
    const layerId = `otp-${sourceLayerName}`
    const onMouseEnter = (e) => {
      mapInstance.getCanvas().style.cursor = 'pointer'
      if (!isMountedRef.current) return
      if (!stickyInfoRef.current && e.features && e.features.length) {
        const feature = e.features[0]
        if (!isMountedRef.current) return
        setHoverInfo(feature)
        if (feature.properties.parentStation) {
          if (!isMountedRef.current) return
          setHighlightParentStation(feature.properties.parentStation)
        }
        if(feature.properties.stops) {
          if (!isMountedRef.current) return
          setHighlightParentStation(feature.properties.gtfsId)
        }
      }
    }
    const onMouseLeave = () => {
      mapInstance.getCanvas().style.cursor = ''
      if (!isMountedRef.current) return
      setHoverInfo(null)
      if (!stickyInfoRef.current) {
        if (!isMountedRef.current) return
        setHighlightParentStation(null)
      }
    }
    const onClick = (e) => {
      if (!isMountedRef.current) return
      setHoverInfo(null)
      if (e.features && e.features.length) {
        const feature = e.features[0]
        if(!feature.properties.stops) {
            if (!isMountedRef.current) return
            setStickyInfo(feature)
        }
        if (feature.properties.parentStation) {
          if (!isMountedRef.current) return
          setHighlightParentStation(feature.properties.parentStation)
        }
        if(feature.properties.stops) {
          if (!isMountedRef.current) return
          setHighlightParentStation(feature.properties.gtfsId)
        }
        const mapInstance = map.getMap();
        mapInstance.flyTo({
            center: feature.geometry.coordinates,
            zoom: 19
        });
      }
    }
    function setEvent(layerId) {
      if (mapInstance.getLayer(layerId)) {
        mapInstance.on('mouseenter', layerId, onMouseEnter)
        mapInstance.on('mouseleave', layerId, onMouseLeave)
        mapInstance.on('click', layerId, onClick)
      }
      /**/
    }

    function removeEvent(layerId) {
      if (mapInstance.getLayer(layerId)) {
        mapInstance.off('mouseenter', layerId, onMouseEnter)
        mapInstance.off('mouseleave', layerId, onMouseLeave)
        mapInstance.off('click', layerId, onClick)
      }
    }

    const styleDataHandler = () => {
      removeEvent(layerId);
      setEvent(layerId);
      if(sourceLayerName === 'stops') {
        removeEvent("otp-stops-highlight");
        removeEvent("otp-stops-platforms");
        setEvent("otp-stops-highlight");
        setEvent("otp-stops-platforms");
      }
    }


    mapInstance.on('styledata', styleDataHandler)
    
    return () => {
      removeEvent(layerId);
      mapInstance.off('styledata', styleDataHandler)
      if(sourceLayerName === 'stops') {
        removeEvent("otp-stops-highlight");
        removeEvent("otp-stops-platforms");
      }
    }
  }, [map, sourceLayerName])

  const onStickyClose = () => {
    setStickyInfo(null)
    setHighlightParentStation(null)
  }

  // --- New default popup renderer: Print all properties ---
  const renderPopupContent = (hoverInfo) => {
    if (mergedConfig.popupRenderer) {
      return mergedConfig.popupRenderer(hoverInfo.properties, hoverInfo, setViewedStop, setLocation)
    }
    return (
      <div style={{ maxHeight: '300px', overflowY: 'auto' }}>
        {Object.entries(properties).map(([key, value]) => (
          <div key={key}>
            <strong>{key}</strong>: {JSON.stringify(value)}
          </div>
        ))}
      </div>
    )
  }

  return (
    <>
      {/* Render the main layer */}
      <Layer id={`otp-${sourceLayerName}`} {...mergedConfig} />
      {/* For stops, render extra highlight layers */}
      {sourceLayerName === 'stops' && (
        <>
          <Layer
            id="otp-stops-highlight"
            type="circle"
            source="otp-source"
            {...{ 'source-layer': sourceLayerName }}
            // Draw the halo under the stop markers, not over them.
            beforeId={`otp-${sourceLayerName}`}
            layout={{}}
            paint={{
              'circle-radius': 20,
              'circle-color': '#0000FF',
              'circle-opacity': 0.2,
              // Pin markers sit above the stop coordinate: move the halo onto the pin body.
              'circle-translate': [0, -16]
            }}
            filter={[
              'all',
              // Without this, a null highlightParentStation matches every stop that has no parent station.
              ['has', 'parentStation'],
              ['==', ['get', 'parentStation'], highlightParentStation],
              ['has', 'routes'],
              [
                'case',
                ['==', ['get', 'routes'], '[]'],
                false,
                ['>', ['length', ['get', 'routes']], 0]
              ]
            ]}
            minzoom={16}
            maxzoom={20}
          />
          <Layer
            id="otp-stops-platforms"
            type="symbol"
            source="otp-source"
            {...{ 'source-layer': sourceLayerName }}
            beforeId={`otp-${sourceLayerName}`}
            layout={{
              'text-field': ['get', 'name'],
              'text-size': 12,
              'text-offset': [0, -1.28],
              'text-anchor': 'bottom',
              'text-allow-overlap': false
            }}
            paint={{
              'text-color': '#000000',
              'text-halo-color': '#ffffff',
              'text-halo-width': 1
            }}
            filter={[
              'all',
              ['has', 'platform'],
              ['has', 'routes'],
              [
                'case',
                ['==', ['get', 'routes'], '[]'],
                false,
                ['>', ['length', ['get', 'routes']], 0]
              ]
            ]}
            minzoom={16}
            maxzoom={20}
          />
        </>
      )}
      {/* Realtime state of stops, stations and rentals is the marker border colour. */}
      {/* Parking availability indicators are now part of the icon variant. */}
      {/*{hoverInfo && (
        <Popup
          maxWidth="none"
          longitude={hoverInfo.geometry.coordinates[0]}
          latitude={hoverInfo.geometry.coordinates[1]}
          closeButton={false}
          offsetTop={-10}
          anchor="top"
        >
          {renderPopupContent(hoverInfo)}
        </Popup>
      )}*/}
      {stickyInfo && (
        <Popup
          maxWidth="none"
          longitude={stickyInfo.geometry.coordinates[0]}
          latitude={stickyInfo.geometry.coordinates[1]}
          closeButton={true}
          onClose={onStickyClose}
          offsetTop={-10}
          anchor="top"
        >
          {renderPopupContent(stickyInfo)}
        </Popup>
      )}
    </>
  )
}

const mapStateToProps = (state) => {
  return {
    ...state
  }
}

const mapDispatchToProps = {
  setViewedStop,
  setLocation
}


export default connect(
  mapStateToProps,
  mapDispatchToProps
)(OTPVectorLayer)
