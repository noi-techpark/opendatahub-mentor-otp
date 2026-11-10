// Replaces the vendor `actions/apiV2` module (see the NormalModuleReplacementPlugin
// in craco.config.js), so the OTP GraphQL calls can be moved to the OJP services
// one at a time without modifying the otp-react-redux submodule.
//
// Every action not overridden here is the vendor one. The import below uses the
// alias on purpose: the replacement only matches relative requests to apiV2,
// so this one still resolves to the original vendor file.
import * as OJP from 'ojp-sdk'
import {
  aggregateModes,
  getBannedRoutesFromSubmodes,
  populateSettingWithValue
} from '@opentripplanner/trip-form'
import { decodeQueryParams, DelimitedArrayParam } from 'use-query-params'
import { toDate } from 'date-fns-tz'
import coreUtils from '@opentripplanner/core-utils'
import haversine from 'haversine'
import polyline from '@mapbox/polyline'
import vendorActions from '@otp-react-redux/lib/actions/apiV2'

import {
  assembleBasePath,
  createQueryAction,
  fetchingStopTimesForStop,
  fetchNearbyError,
  fetchNearbyResponse,
  findStopTimesForStopError,
  findStopTimesForStopResponse,
  rememberSearch,
  routingError,
  routingRequest,
  routingResponse,
  updateOtpUrlParams
} from '@otp-react-redux/lib/actions/api'
import { convertToPlace, getPersistenceMode } from '@otp-react-redux/lib/util/user'
import { FETCH_STATUS } from '@otp-react-redux/lib/util/constants'
import {
  generateModeSettingValues,
  getDefaultNumItineraries,
  getServiceStart
} from '@otp-react-redux/lib/util/api'
import {
  getActiveItineraries,
  getActiveItinerary,
  getRouteOperator,
  queryIsValid
} from '@otp-react-redux/lib/util/state'
import {
  getRouteColorBasedOnSettings,
  routeIsValid
} from '@otp-react-redux/lib/util/viewer'
import { rememberPlace } from '@otp-react-redux/lib/actions/user'
import { RoutingQueryCallResult } from '@otp-react-redux/lib/actions/api-constants'

export * from '@otp-react-redux/lib/actions/apiV2'

// OTP's OJP API only knows the "netex" feed: it prefixes every incoming id with
// "netex:" (router-config.json, ojpApi.hardcodedInputFeedId) and returns ids
// without it. Stops of the other feeds (skyalps, amarillo) stay on GraphQL.
const OJP_FEED_PREFIX = 'netex:'

// The server rejects NumberOfResults >= 100, and without a TimeWindow it only
// looks a couple of hours ahead, so a whole service day is read in pages.
const OJP_STOP_EVENTS_PER_PAGE = 99
// Guard against looping forever if the server keeps returning the same page.
const OJP_STOP_EVENTS_MAX_PAGES = 30

function createOjpSdk(config) {
  const { api } = config
  return OJP.SDK.create('OTP-RR', {
    authToken: null,
    url: `${assembleBasePath(config)}${api?.basePath ?? '/otp'}/ojp/v2/`
  })
}

/**
 * Epoch seconds of the OTP "service day" (noon minus 12 hours, local time) of
 * an OJP OperatingDayRef. It is what OTP GraphQL returns as stoptime.serviceDay:
 * the UI adds scheduledDeparture to it, and they differ from midnight on DST days.
 */
function getOtpServiceDay(operatingDayRef, timeZone) {
  return toDate(`${operatingDayRef} 12:00`, { timeZone }).getTime() / 1000 - 43200
}

/**
 * Reads all the departures of a stop in the 24 hours from `start` (for the
 * schedule viewer a service day, 03:30 to 03:30; for the nearby view from now,
 * the windows the vendor GraphQL queries cover) from the OJP StopEventRequest,
 * one page at a time: each page starts from the last departure of the
 * previous one, and the repeated departures are dropped.
 */
async function fetchOjpStopEvents(sdk, stopRef, start) {
  const serviceEnd = start.getTime() + 24 * 3600 * 1000
  const stopEvents = []
  const seen = new Set()
  let pageStart = start

  for (let page = 0; page < OJP_STOP_EVENTS_MAX_PAGES; page++) {
    const request = sdk.requests.StopEventRequest.initWithPlaceRefAndDate(
      stopRef,
      pageStart
    )
    // timeWindow is not part of the SDK params type, but the SDK serializes
    // the keys in object order, which here follows the XSD sequence.
    request.payload.params = {
      includeAllRestrictedLines: true,
      numberOfResults: OJP_STOP_EVENTS_PER_PAGE,
      timeWindow: 'PT24H',
      stopEventType: 'departure',
      includePreviousCalls: false,
      includeOnwardCalls: false,
      useRealtimeData: 'explanatory'
    }
    const response = await request.fetchResponse(sdk)
    if (!response.ok) throw response.error

    const results = response.value.stopEventResult ?? []
    let added = 0
    results.forEach(({ stopEvent }) => {
      const { operatingDayRef, journeyRef } = stopEvent.service
      const key = `${operatingDayRef}|${journeyRef}`
      const departure = new Date(
        stopEvent.thisCall.callAtStop.serviceDeparture.timetabledTime
      )
      if (seen.has(key) || departure.getTime() >= serviceEnd) return
      seen.add(key)
      stopEvents.push(stopEvent)
      added++
    })

    const lastDeparture = new Date(
      results[results.length - 1]?.stopEvent.thisCall.callAtStop.serviceDeparture.timetabledTime
    )
    if (
      results.length < OJP_STOP_EVENTS_PER_PAGE ||
      added === 0 ||
      lastDeparture.getTime() >= serviceEnd
    ) {
      break
    }
    pageStart = lastDeparture
  }
  return stopEvents
}

/**
 * Builds the `stoptimesForPatterns` of the vendor GraphQL StopTimes query from
 * OJP stop events. OJP has no patterns, so one pattern is made per line and
 * destination: the schedule viewer groups by route id and headsign anyway.
 */
function toStoptimesForPatterns(stopEvents, stopData, timeZone) {
  const stopRef = stopData.gtfsId.slice(OJP_FEED_PREFIX.length)
  const patterns = {}

  stopEvents.forEach(({ service, thisCall }) => {
    const { callAtStop } = thisCall
    // Same filters as the vendor query: omitNonPickups, and no arrivals at the
    // terminus (the vendor drops them with isLastStop).
    if (callAtStop.noBoardingAtStop) return
    if (service.destinationStopPointRef === stopRef) return

    const routeId = `${OJP_FEED_PREFIX}${service.lineRef}`
    const route = stopData.routes.find((r) => r.id === routeId)
    if (!routeIsValid(route, routeId)) return

    const headsign = service.destinationText?.text
    const id = `${routeId}:${headsign}`
    if (!patterns[id]) {
      patterns[id] = {
        pattern: {
          desc: headsign,
          headsign,
          id,
          route: { agency: { gtfsId: route.agency?.gtfsId }, gtfsId: routeId }
        },
        stoptimes: []
      }
    }

    const serviceDay = getOtpServiceDay(service.operatingDayRef, timeZone)
    const departure =
      new Date(callAtStop.serviceDeparture.timetabledTime).getTime() / 1000
    patterns[id].stoptimes.push({
      headsign,
      scheduledDeparture: departure - serviceDay,
      serviceDay,
      trip: {
        blockId: null,
        id: `${OJP_FEED_PREFIX}${service.journeyRef}`,
        pattern: { id },
        route: { gtfsId: routeId }
      }
    })
  })
  return Object.values(patterns)
}

/**
 * Departures of a stop from the OJP StopEventRequest (#343). The stop and its
 * routes (names, colors, agency) still come from GraphQL: OJP does not return them.
 */
export const findStopTimesForStop = (params) =>
  function (dispatch, getState) {
    const { date, stopId } = params
    if (!stopId?.startsWith(OJP_FEED_PREFIX)) {
      return dispatch(vendorActions.findStopTimesForStop(params))
    }

    dispatch(fetchingStopTimesForStop(params))
    const { config } = getState().otp
    const timeZone = config.homeTimezone

    // The response action is a thunk: the GraphQL stop is only the first half of
    // the data, the OJP departures are added before the vendor action is sent.
    const withOjpStopTimes = (stopData) =>
      async function (dispatch) {
        if (!stopData) {
          // Same payload the vendor action sends for an unknown stop.
          return dispatch(
            findStopTimesForStopResponse({
              fetchStatus: FETCH_STATUS.FETCHED,
              stopTimesLastUpdated: new Date().getTime()
            })
          )
        }
        try {
          const stopEvents = await fetchOjpStopEvents(
            createOjpSdk(config),
            stopId.slice(OJP_FEED_PREFIX.length),
            getServiceStart(date, timeZone)
          )
          dispatch(
            findStopTimesForStopResponse({
              ...stopData,
              fetchStatus: FETCH_STATUS.FETCHED,
              stoptimesForPatterns: toStoptimesForPatterns(
                stopEvents,
                stopData,
                timeZone
              ),
              stopTimesLastUpdated: new Date().getTime()
            })
          )
        } catch (err) {
          dispatch(findStopTimesForStopError(err))
        }
      }

    // The vendor StopTimes query without stoptimesForPatterns.
    const query = `query StopTimes($stopId: String!) {
      stop(id: $stopId) {
        gtfsId
        code
        platformCode
        lat
        lon
        locationType
        name
        wheelchairBoarding
        routes {
          id: gtfsId
          agency {
            gtfsId
            name
          }
          longName
          mode
          color
          textColor
          shortName
          patterns {
            id
            headsign
          }
        }
      }
    }`

    return dispatch(
      createQueryAction(null, withOjpStopTimes, findStopTimesForStopError, {
        fetchOptions: {
          body: JSON.stringify({ query, variables: { stopId } }),
          headers: { 'Content-Type': 'application/json' },
          method: 'POST'
        },
        noThrottle: true,
        rewritePayload: (payload) => {
          if (payload.errors) throw payload.errors
          return payload.data?.stop
        },
        url: '/gtfs/v1'
      })
    )
  }

/**
 * Builds the `stoptimesForPatterns` of the vendor GraphQL Nearby query from OJP
 * stop events. Unlike the StopTimes query, Nearby asks for realtime and applies
 * no filters (no omitNonPickups, terminus arrivals included), so neither is done here.
 *
 * The nearby rows are labelled with the GraphQL pattern headsign, which OJP does
 * not have, and the trip destination text is not a stable substitute: trips with
 * realtime updates carry a bilingual one ("Südtiroler Straße Via Alto Adige" for
 * "Via Alto Adige"). So the departures are grouped by line and destination stop,
 * and each group is labelled with the text of a departure without realtime data.
 * The rows still differ from the GraphQL ones where a pattern headsign is not
 * the trip destination (agreed UX change, see #343).
 */
function toNearbyStoptimesForPatterns(stopEvents, stop, timeZone) {
  const labels = {}
  stopEvents.forEach(({ service, thisCall }) => {
    const key = `${service.lineRef}|${service.destinationStopPointRef}`
    const realtime = !!thisCall.callAtStop.serviceDeparture.estimatedTime
    if (!labels[key] || (labels[key].realtime && !realtime)) {
      labels[key] = { realtime, text: service.destinationText?.text }
    }
  })

  const patterns = {}

  stopEvents.forEach(({ service, thisCall }) => {
    const { callAtStop } = thisCall
    const routeId = `${OJP_FEED_PREFIX}${service.lineRef}`
    const route = stop.routes.find((r) => r.gtfsId === routeId)
    if (!route) return

    const headsign =
      labels[`${service.lineRef}|${service.destinationStopPointRef}`].text
    const id = `${routeId}:${headsign}`
    if (!patterns[id]) {
      patterns[id] = {
        pattern: {
          desc: headsign,
          headsign,
          route: {
            agency: route.agency,
            color: route.color,
            longName: route.longName,
            mode: route.mode,
            shortName: route.shortName,
            textColor: route.textColor,
            type: route.type
          }
        },
        stoptimes: []
      }
    }

    // OTP GraphQL marks the departures with realtime data as MODIFIED, and
    // OJP returns an EstimatedTime for exactly those (checked trip by trip).
    const { estimatedTime, timetabledTime } = callAtStop.serviceDeparture
    const serviceDay = getOtpServiceDay(service.operatingDayRef, timeZone)
    const scheduled = new Date(timetabledTime).getTime() / 1000
    const expected = new Date(estimatedTime ?? timetabledTime).getTime() / 1000
    patterns[id].stoptimes.push({
      departureDelay: expected - scheduled,
      headsign,
      realtimeDeparture: expected - serviceDay,
      realtimeState: estimatedTime ? 'MODIFIED' : 'SCHEDULED',
      scheduledDeparture: scheduled - serviceDay,
      serviceDay,
      trip: { route: { shortName: route.shortName } }
    })
  })
  return Object.values(patterns)
}

// Copy of the vendor mergeSameStops (actions/apiV2.js), which is not exported:
// stops sharing a code are shown as one, with the departures of both.
const mergeSameStops = (nearbyResponse) => {
  return nearbyResponse?.reduce((prev, { node }) => {
    const existingStop = prev.find(
      (stop) => stop.place.code === node.place.code
    )
    // Only merge if the stop has a code at all
    if (existingStop && node.place.code) {
      existingStop.place.stoptimesForPatterns = [
        ...(existingStop.place.stoptimesForPatterns || []),
        ...(node.place.stoptimesForPatterns || [])
      ]
    } else {
      prev.push(node)
    }
    return prev
  }, [])
}

// The vendor stoptimesForPatterns selection of the Nearby query, still used for
// the stops OJP cannot resolve (feeds other than netex).
const NEARBY_STOPTIMES_FOR_PATTERNS = `stoptimesForPatterns {
  pattern {
    headsign
    desc: name
    route {
      agency {
        name
        gtfsId
      }
      shortName
      type
      mode
      longName
      color
      textColor
    }
  }
  stoptimes {
    serviceDay
    departureDelay
    realtimeState
    realtimeDeparture
    scheduledDeparture
    headsign
    trip {
      route {
        shortName
      }
    }
  }
}`

/**
 * Nearby view with the departures from the OJP StopEventRequest (#343). The
 * places (stops, rentals, parkings) still come from the GraphQL `nearest`
 * query, which now asks for the routes of each stop instead of its departures.
 * The view refreshes every 15 s, and every refresh reads the 24 hours from now
 * for each stop (one OJP request per 99 departures), like the GraphQL query.
 */
export const fetchNearby = (position, radius) =>
  function (dispatch, getState) {
    const { lat, lon } = position
    const { config } = getState().otp
    const timeZone = config.homeTimezone

    // The response action is a thunk: the GraphQL places are only the first
    // half of the data, the departures are added before the vendor action is sent.
    const withOjpStoptimes = (edges) =>
      async function (dispatch) {
        try {
          const sdk = createOjpSdk(config)
          const now = new Date()
          const stops = (edges || [])
            .map(({ node }) => node.place)
            .filter((place) => place.__typename === 'Stop')

          await Promise.all(
            stops
              .filter((stop) => stop.gtfsId.startsWith(OJP_FEED_PREFIX))
              .map(async (stop) => {
                const stopEvents = await fetchOjpStopEvents(
                  sdk,
                  stop.gtfsId.slice(OJP_FEED_PREFIX.length),
                  now
                )
                stop.stoptimesForPatterns = toNearbyStoptimesForPatterns(
                  stopEvents,
                  stop,
                  timeZone
                )
              })
          )

          const otherFeedStops = stops.filter(
            (stop) => !stop.gtfsId.startsWith(OJP_FEED_PREFIX)
          )
          if (otherFeedStops.length > 0) {
            const { api } = config
            const response = await fetch(
              `${assembleBasePath(config)}${api?.basePath ?? '/otp'}/gtfs/v1`,
              {
                body: JSON.stringify({
                  query: `query NearbyOtherFeeds($ids: [String]) {
                    stops(ids: $ids) {
                      gtfsId
                      ${NEARBY_STOPTIMES_FOR_PATTERNS}
                    }
                  }`,
                  variables: { ids: otherFeedStops.map((stop) => stop.gtfsId) }
                }),
                headers: { 'Content-Type': 'application/json' },
                method: 'POST'
              }
            )
            const payload = await response.json()
            if (payload.errors) throw payload.errors
            payload.data.stops.forEach(({ gtfsId, stoptimesForPatterns }) => {
              const stop = otherFeedStops.find((s) => s.gtfsId === gtfsId)
              stop.stoptimesForPatterns = stoptimesForPatterns
            })
          }

          dispatch(
            fetchNearbyResponse({
              coords: { lat, lon },
              data: mergeSameStops(edges)
            })
          )
        } catch (err) {
          dispatch(fetchNearbyError(err))
        }
      }

    // The vendor Nearby query, with the routes of each stop in place of
    // stoptimesForPatterns.
    const query = `query Nearby(
      $lat: Float!
      $lon: Float!
      $radius: Int
    ) {
      nearest(lat:$lat, lon:$lon, maxDistance: $radius, first: 100, filterByPlaceTypes: [STOP, VEHICLE_RENT, BIKE_PARK, CAR_PARK]) {
        edges {
          node {
            id
            distance
            place {
              __typename
              id
              lat
              lon
              ...on RentalVehicle {
                network
                name
                lat
                lon
                allowPickupNow
                operative
                rentalUris {
                  android
                  ios
                  web
                }
                vehicleType {
                  formFactor
                }
              }
              ... on VehicleRentalStation {
                network
              }
              ...on BikeRentalStation {
                bikesAvailable
                spacesAvailable
                name
                networks
              }
              ... on VehicleParking {
                carPlaces
                bicyclePlaces
                lat
                lon
                name
              }
              ... on Stop {
                name
                lat
                lon
                code
                platformCode
                gtfsId
                routes {
                  gtfsId
                  agency {
                    name
                    gtfsId
                  }
                  shortName
                  type
                  mode
                  longName
                  color
                  textColor
                }
              }
            }
            distance
          }
        }
      }
    }`

    return dispatch(
      createQueryAction(null, withOjpStoptimes, fetchNearbyError, {
        fetchOptions: {
          body: JSON.stringify({ query, variables: { lat, lon, radius } }),
          headers: { 'Content-Type': 'application/json' },
          method: 'POST'
        },
        noThrottle: true,
        rewritePayload: (payload) => {
          // Same error the vendor action raises.
          if (payload.errors) {
            const error = new Error('GraphQL response error')
            error.message =
              'Check error.cause for more information. Are the OTP server and client on compatible versions?'
            error.cause = payload.errors
            throw error
          }
          return payload.data?.nearest?.edges
        },
        url: '/gtfs/v1'
      })
    )
  }

// Helpers of the vendor routingQuery, copied with it from actions/apiV2.js
// because they are not exported.
const { generateCombinations, generateOtp2Query, SIMPLIFICATIONS } =
  coreUtils.queryGen
const { getTripOptionsFromQuery, getUrlParams } = coreUtils.query
const { convertGraphQLResponseToLegacy } = coreUtils.itinerary
const { randId } = coreUtils.storage

function formatRecentPlace(place) {
  return convertToPlace({
    ...place,
    icon: 'clock-o',
    id: `recent-${randId()}`,
    timestamp: new Date().getTime(),
    type: 'recent'
  })
}

function formatRecentSearch(state, queryParamData) {
  return {
    id: randId(),
    query: getTripOptionsFromQuery(
      { ...state.otp.currentQuery, queryParamData },
      true
    ),
    timestamp: new Date().getTime()
  }
}

function isStoredPlace(place) {
  return ['home', 'work', 'suggested', 'stop'].indexOf(place.type) !== -1
}

function createGraphQLQueryAction(
  query,
  variables,
  responseAction,
  errorAction,
  options
) {
  const fetchOptions = {
    body: JSON.stringify({ batchId: options.batchId, query, variables }),
    headers: { 'Content-Type': 'application/json' },
    method: 'POST'
  }
  return createQueryAction(null, responseAction, errorAction, {
    ...options,
    fetchOptions,
    noThrottle: true,
    url: '/gtfs/v1'
  })
}

// OJP ptMode -> OTP mode. bus, rail and cableway (the South Tyrol gondolas)
// are checked against the OTP OJP API; the others follow the OJP enumeration.
const OJP_PT_MODES = {
  air: 'AIRPLANE',
  bus: 'BUS',
  cableway: 'GONDOLA',
  coach: 'COACH',
  funicular: 'FUNICULAR',
  metro: 'SUBWAY',
  rail: 'RAIL',
  telecabin: 'GONDOLA',
  tram: 'TRAM',
  trolleyBus: 'TROLLEYBUS',
  water: 'FERRY'
}

// OTP transit mode -> OJP ptMode, for the mode filter of the request. The
// "transit" button sends its submodes (BUS, TRAM, RAIL, ...) and not FUNICULAR,
// so they must reach OJP too, or OJP would return modes GraphQL leaves out.
const OTP_TRANSIT_MODES_TO_OJP = {
  AIRPLANE: 'air',
  BUS: 'bus',
  COACH: 'coach',
  FERRY: 'water',
  FUNICULAR: 'funicular',
  GONDOLA: 'cableway',
  RAIL: 'rail',
  SUBWAY: 'metro',
  TRAM: 'tram',
  TROLLEYBUS: 'trolleyBus'
}

/**
 * Only the plain public transport search (the "transit" mode button: transit
 * modes with walking) goes to the OJP TripRequest (#342). The other
 * combinations (bicycle, rentals, car, park and ride), transit modes without an
 * OJP equivalent and the options the OTP OJP API ignores (wheelchair, banned or
 * unpreferred routes and trips, checked on the test server) stay on GraphQL,
 * so their results do not change.
 */
function isOjpCombination(combo) {
  const { banned, modes, modeSettings, unpreferred } = combo
  const wheelchair = modeSettings?.find((ms) => ms.key === 'wheelchair')?.value
  return (
    modes.every(
      ({ mode, qualifier }) =>
        !qualifier &&
        (mode === 'TRANSIT' || !!OTP_TRANSIT_MODES_TO_OJP[mode])
    ) &&
    !wheelchair &&
    !banned?.routes &&
    !banned?.trips &&
    !unpreferred?.routes &&
    !unpreferred?.agencies
  )
}

// A stop chosen in the search is sent by id, like the core-utils patch does for
// GraphQL; any other place (or a stop outside the netex feed) by coordinates.
function toOjpPlaceRef(place) {
  return place.type === 'stop' && place.id?.startsWith(OJP_FEED_PREFIX)
    ? place.id.slice(OJP_FEED_PREFIX.length)
    : `${place.lat},${place.lon}`
}

// "PT6M13S" -> 373
function parseOjpDuration(duration) {
  const [, h = 0, m = 0, s = 0] =
    duration?.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/) || []
  return Number(h) * 3600 + Number(m) * 60 + Number(s)
}

/**
 * Converts an OJP Trip into an itinerary of the vendor GraphQL Plan query, so
 * that the rest of routingQuery and the UI handle it as before.
 *
 * Agreed approximations while OTP returns no LegTrack/LegProjection (#342):
 * - transit legs are drawn as straight lines between their stops, and walking
 *   legs as a straight line between start and end, without turn-by-turn steps;
 * - OJP has no route long name (the headsign takes its place), color, agency,
 *   fares or alerts, so they are empty.
 */
function toPlanItinerary(trip, places, from, to) {
  // OJP names the searched places "Origin" and "Destination"; GraphQL uses the
  // names sent with the search, so those are used here too.
  const placeOf = (location, searchedPlace) =>
    location.stopPointRef
      ? places[location.stopPointRef]
      : {
          lat: location.geoPosition.latitude,
          lon: location.geoPosition.longitude,
          name: searchedPlace?.name ?? location.name?.text
        }
  const stopOf = (stopPointRef) => {
    const { lat, lon } = places[stopPointRef]
    const gtfsId = `${OJP_FEED_PREFIX}${stopPointRef}`
    return { alerts: [], code: null, gtfsId, id: gtfsId, lat, lon }
  }

  const legs = []
  trip.leg.forEach((leg, legIndex) => {
    if (leg.timedLeg) {
      const { legAlight, legBoard, legIntermediate = [], service } =
        leg.timedLeg
      const calls = [legBoard, ...legIntermediate, legAlight]
      const points = calls.map((call) => places[call.stopPointRef])
      const departure = legBoard.serviceDeparture
      const arrival = legAlight.serviceArrival
      const startTime = new Date(
        departure.estimatedTime ?? departure.timetabledTime
      ).getTime()
      const endTime = new Date(
        arrival.estimatedTime ?? arrival.timetabledTime
      ).getTime()
      const routeId = `${OJP_FEED_PREFIX}${service.lineRef}`
      const tripId = `${OJP_FEED_PREFIX}${service.journeyRef}`
      legs.push({
        accessibilityScore: null,
        agency: null,
        alerts: [],
        arrivalDelay:
          (endTime - new Date(arrival.timetabledTime).getTime()) / 1000,
        departureDelay:
          (startTime - new Date(departure.timetabledTime).getTime()) / 1000,
        // The itinerary card picks its main leg by distance, so transit legs
        // need one: measured along the stops, like the drawn line.
        distance: points.slice(1).reduce(
          (sum, point, i) =>
            sum +
            haversine(
              { latitude: points[i].lat, longitude: points[i].lon },
              { latitude: point.lat, longitude: point.lon },
              { unit: 'meter' }
            ),
          0
        ),
        dropOffBookingInfo: null,
        dropoffType: 'SCHEDULED',
        duration: (endTime - startTime) / 1000,
        endTime,
        fareProducts: [],
        from: {
          ...places[legBoard.stopPointRef],
          rentalVehicle: null,
          stop: stopOf(legBoard.stopPointRef),
          vertexType: 'TRANSIT'
        },
        headsign: service.destinationText?.text,
        interlineWithPreviousLeg: false,
        intermediateStops: legIntermediate.map((call) => ({
          ...places[call.stopPointRef],
          locationType: 'STOP',
          stopCode: null,
          stopId: `${OJP_FEED_PREFIX}${call.stopPointRef}`
        })),
        legGeometry: {
          length: points.length,
          points: polyline.encode(points.map(({ lat, lon }) => [lat, lon]))
        },
        mode: OJP_PT_MODES[service.mode.ptMode] ?? 'TRANSIT',
        pickupBookingInfo: null,
        pickupType: 'SCHEDULED',
        // Same mapping as the nearby view: GraphQL marks realtime legs as MODIFIED.
        realTime: !!departure.estimatedTime,
        realtimeState: departure.estimatedTime ? 'MODIFIED' : 'SCHEDULED',
        rentedBike: false,
        rideHailingEstimate: null,
        route: {
          alerts: [],
          color: null,
          gtfsId: routeId,
          id: routeId,
          // OJP has no long name, and the leg header (metro RouteBlock) shows
          // the destination only when there is one: with the headsign in its
          // place the header shows the destination alone, as GraphQL does when
          // the long name is similar to it.
          longName: service.destinationText?.text,
          // The XML parser turns numeric line names (201) into numbers.
          shortName: String(
            service.publishedServiceName?.text ?? service.publicCode
          ),
          textColor: null,
          type: null
        },
        startTime,
        steps: [],
        to: {
          ...places[legAlight.stopPointRef],
          rentalVehicle: null,
          stop: stopOf(legAlight.stopPointRef),
          vertexType: 'TRANSIT'
        },
        transitLeg: true,
        trip: {
          arrivalStoptime: {
            stop: { gtfsId: `${OJP_FEED_PREFIX}${legAlight.stopPointRef}` },
            stopPosition: legAlight.order
          },
          departureStoptime: {
            stop: { gtfsId: `${OJP_FEED_PREFIX}${legBoard.stopPointRef}` },
            stopPosition: legBoard.order
          },
          gtfsId: tripId,
          id: tripId
        }
      })
      return
    }

    // A walking leg: continuousLeg, or transferLeg between two stops.
    const walk = leg.continuousLeg ?? leg.transferLeg
    const walkFrom = placeOf(walk.legStart, legIndex === 0 && from)
    const walkTo = placeOf(
      walk.legEnd,
      legIndex === trip.leg.length - 1 && to
    )
    // A transferLeg has no time window: it starts when the previous leg ends.
    const startTime = walk.timeWindowStart
      ? new Date(walk.timeWindowStart).getTime()
      : legs[legs.length - 1]?.endTime
    const duration = parseOjpDuration(walk.duration)
    legs.push({
      accessibilityScore: null,
      agency: null,
      alerts: [],
      arrivalDelay: 0,
      departureDelay: 0,
      distance: walk.length,
      duration,
      endTime: walk.timeWindowEnd
        ? new Date(walk.timeWindowEnd).getTime()
        : startTime + duration * 1000,
      fareProducts: [],
      from: {
        ...walkFrom,
        rentalVehicle: null,
        stop: walk.legStart.stopPointRef
          ? stopOf(walk.legStart.stopPointRef)
          : null,
        vertexType: walk.legStart.stopPointRef ? 'TRANSIT' : 'NORMAL'
      },
      headsign: null,
      interlineWithPreviousLeg: false,
      intermediateStops: null,
      legGeometry: {
        length: 2,
        points: polyline.encode([
          [walkFrom.lat, walkFrom.lon],
          [walkTo.lat, walkTo.lon]
        ])
      },
      mode: 'WALK',
      realTime: false,
      realtimeState: null,
      rentedBike: false,
      route: null,
      startTime,
      steps: [],
      to: {
        ...walkTo,
        rentalVehicle: null,
        stop: walk.legEnd.stopPointRef ? stopOf(walk.legEnd.stopPointRef) : null,
        vertexType: walk.legEnd.stopPointRef ? 'TRANSIT' : 'NORMAL'
      },
      transitLeg: false,
      trip: null
    })
  })

  const startTime = legs[0].startTime
  const endTime = legs[legs.length - 1].endTime
  const duration = (endTime - startTime) / 1000
  const walkLegs = legs.filter((leg) => leg.mode === 'WALK')
  return {
    accessibilityScore: null,
    duration,
    endTime,
    legs,
    startTime,
    // OJP Transfers counts more than the changes of vehicle (4 against the
    // GraphQL 1 on the same trip), so it is computed like GraphQL does.
    transfers: Math.max(0, legs.filter((leg) => leg.transitLeg).length - 1),
    waitingTime: duration - legs.reduce((sum, leg) => sum + leg.duration, 0),
    walkTime: walkLegs.reduce((sum, leg) => sum + leg.duration, 0)
  }
}

/**
 * Same as the GraphQL Plan request of one combination, from the OJP
 * TripRequest: the response is shaped as the GraphQL one and goes through the
 * same rewritePayload and response action.
 */
function ojpPlanQueryAction(combo, responseAction, errorAction, options) {
  return async function (dispatch, getState) {
    const { config } = getState().otp
    const { arriveBy, date, from, modes, numItineraries, time, to } = combo

    let rewrittenPayload
    try {
      const sdk = createOjpSdk(config)
      const request = sdk.requests.TripRequest.initWithPlaceRefsOrCoords(
        toOjpPlaceRef(from),
        toOjpPlaceRef(to)
      )
      const dateTime = toDate(`${date} ${time}`, {
        timeZone: config.homeTimezone
      }).toISOString()
      if (arriveBy) {
        delete request.payload.origin.depArrTime
        request.payload.destination.depArrTime = dateTime
      } else {
        request.payload.origin.depArrTime = dateTime
      }
      request.payload.params.numberOfResults = numItineraries
      const ptModes = modes
        .filter(({ mode }) => mode !== 'TRANSIT')
        .map(({ mode }) => OTP_TRANSIT_MODES_TO_OJP[mode])
      if (ptModes.length > 0) {
        request.payload.params.modeAndModeOfOperationFilter = [
          { exclude: false, personalMode: [], ptMode: ptModes }
        ]
      }
      const response = await request.fetchResponse(sdk)
      if (!response.ok) throw response.error

      const places = {}
      response.value.tripResponseContext?.places?.place?.forEach((place) => {
        const ref = place.stopPoint?.stopPointRef
        if (!ref) return
        places[ref] = {
          lat: place.geoPosition.latitude,
          lon: place.geoPosition.longitude,
          name: place.stopPoint.stopPointName?.text
        }
      })

      rewrittenPayload = options.rewritePayload(
        {
          data: {
            plan: {
              itineraries: (response.value.tripResult ?? []).map(({ trip }) =>
                toPlanItinerary(trip, places, from, to)
              ),
              routingErrors: []
            }
          }
        },
        dispatch,
        getState
      )
    } catch (err) {
      return dispatch(errorAction(err))
    }
    dispatch(responseAction(rewrittenPayload))
  }
}

const queryParamConfig = { modeButtons: DelimitedArrayParam }

/**
 * The vendor routingQuery (actions/apiV2.js), copied because each combination
 * request is built inside it: the only change is that the combination chosen by
 * isOjpCombination is sent to the OJP TripRequest instead of GraphQL.
 */
export function routingQuery(searchId = null, updateSearchInReducer) {
  // eslint-disable-next-line complexity
  return function (dispatch, getState) {
    const state = getState()
    const { config, currentQuery, modeSettingDefinitions } = state.otp
    const persistenceMode = getPersistenceMode(config.persistence)
    const activeItinerary =
      getActiveItinerary(state) ||
      (config.itinerary?.showFirstResultByDefault ? 0 : null)

    const isNewSearch = !searchId
    if (isNewSearch) searchId = randId()
    // Don't permit a routing query if the query is invalid
    if (!queryIsValid(state)) {
      console.warn('Query is invalid. Aborting routing query', currentQuery)
      return RoutingQueryCallResult.INVALID_QUERY
    }

    const {
      bannedTrips,
      date,
      departArrive,
      modes,
      numItineraries,
      routingType,
      time,
      unpreferred
    } = currentQuery
    const arriveBy = departArrive === 'ARRIVE'

    // Retrieve active mode keys from URL parameters or configuration defaults
    const urlSearchParams = new URLSearchParams(getUrlParams())
    const activeModeKeys =
      decodeQueryParams(queryParamConfig, {
        modeButtons: urlSearchParams.get('modeButtons')
      }).modeButtons ||
      config?.modes?.initialState?.enabledModeButtons ||
      {}

    const strictModes = !!config?.itinerary?.strictItineraryFiltering

    // Filter mode definitions based on active mode keys
    const activeModeButtons = config.modes?.modeButtons.filter((mb) =>
      activeModeKeys.includes(mb.key)
    )
    const activeModes = aggregateModes(activeModeButtons)

    // Get mode setting values from the url, or initial state config, or default value in definition
    const modeSettingValues = generateModeSettingValues(
      urlSearchParams,
      modeSettingDefinitions,
      config?.modes?.initialState?.modeSettingValues
    )
    // TODO: walkReluctance is in here, but not when set via setQueryParam
    const modeSettings = modeSettingDefinitions?.map(
      populateSettingWithValue(modeSettingValues)
    )
    // Get the raw query param strings to save for the rider's search history
    const rawModeButtonQP = urlSearchParams.get('modeButtons')
    const queryParamData = {
      modeButtons: rawModeButtonQP,
      ...modeSettingValues
    }

    const excludedRoutes =
      config.routeModeOverrides &&
      getBannedRoutesFromSubmodes(
        modeSettings,
        config.routeModeOverrides
      )?.join(',')

    const baseQuery = {
      arriveBy,
      banned: {
        routes: excludedRoutes || undefined,
        trips: bannedTrips
      },
      date,
      from: currentQuery.from,
      modes: modes || activeModes,
      modeSettings,
      time,
      to: currentQuery.to,
      unpreferred,
      // TODO: Does this break everything?
      ...currentQuery,
      numItineraries: numItineraries || getDefaultNumItineraries(config)
    }
    // Generate combinations if the modes for query are not specified in the query
    // FIXME: BICYCLE_RENT does not appear in this list unless TRANSIT is also enabled.
    // This is likely due to the fact that BICYCLE_RENT is treated as a transit submode.
    const combinations = modes ? [baseQuery] : generateCombinations(baseQuery)

    if (combinations.length === 0) {
      return RoutingQueryCallResult.INVALID_MODE_SELECTION
    }

    dispatch(
      routingRequest({
        activeItinerary,
        pending: combinations.length,
        routingType,
        searchId,
        updateSearchInReducer
      })
    )

    combinations.forEach((combo, index) => {
      const responseAction = (response) => {
        const dispatchedRoutingResponse = routingResponse(response)
        // If tracking is enabled, store locations and search after successful
        // search is completed.
        if (
          persistenceMode.isLocalStorage &&
          state.user?.localUser?.storeTripHistory
        ) {
          const { from, to } = currentQuery
          if (!isStoredPlace(from)) {
            dispatch(
              rememberPlace({
                location: formatRecentPlace(from),
                type: 'recent'
              })
            )
          }
          if (!isStoredPlace(to)) {
            dispatch(
              rememberPlace({
                location: formatRecentPlace(to),
                type: 'recent'
              })
            )
          }
          dispatch(rememberSearch(formatRecentSearch(state, queryParamData)))
        }
        return dispatchedRoutingResponse
      }
      const options = {
        batchId: searchId,
        rewritePayload: (response, dispatch, getState) => {
          const itineraries = response.data?.plan?.itineraries

          // Convert user-selected transit modes from mode selector into modes recognized by OTP.
          const activeModeStrings = activeModes.map(
            (am) => SIMPLIFICATIONS[am.mode]
          )

          let filteredItineraries = itineraries
          // If "strictItineraryFiltering" is enabled, only return itineraries that contain at least one explicitly requested mode...
          if (strictModes) {
            filteredItineraries = itineraries?.filter((itin) =>
              itin.legs.some((leg) =>
                activeModeStrings.includes(SIMPLIFICATIONS[leg.mode])
              )
            )
            // ... Otherwise return all itineraries.
          }

          // Filter itineraries to collapse short names and hide unnecessary errors.
          const withCollapsedShortNames = filteredItineraries?.map((itin) => ({
            ...itin,
            legs: itin.legs
              ?.map((leg) => {
                return {
                  ...leg,
                  origColor: leg?.route?.color,
                  route: {
                    ...leg.route,
                    color: getRouteColorBasedOnSettings(
                      getRouteOperator(
                        {
                          agencyId: leg?.agency?.id,
                          id: leg?.route?.id
                        },
                        config.transitOperators
                      ),
                      { color: leg?.route?.color, mode: leg.mode }
                    ).split('#')?.[1]
                  }
                }
              })
              ?.map(convertGraphQLResponseToLegacy)
          }))

          /* It is possible for a NO_TRANSIT_CONNECTION error to be
            returned even if trips were returned, since it is on a mode-by-mode basis.
            there is a chance for user confusion!

           We'll reintroduce this error later once all the results are compiled */

          response.data.plan.routingErrors =
            response.data?.plan?.routingErrors.filter(
              (re) => re?.code !== 'NO_TRANSIT_CONNECTION'
            )
          if (
            getActiveItineraries(getState())?.length > 0 &&
            response?.data?.plan
          ) {
            // Add accessibility error, if it is turned on
            const state = getState()
            const { displayA11yError } = state.otp.config?.itinerary
            if (displayA11yError) {
              if (
                response.data.plan.itineraries.find(
                  (itin) => !!itin?.accessibilityScore
                )
              ) {
                if (!response.data.plan?.routingErrors) {
                  response.data.plan.routingErrors = []
                }
                response.data.plan.routingErrors.push({
                  code: 'OTP_RR_A11Y_ROUTING_ENABLED'
                })
              }
            }
          }

          return {
            index,
            response: {
              plan: {
                ...response.data?.plan,
                itineraries: withCollapsedShortNames
              },
              requestId: searchId
            },
            searchId
          }
        }
      }

      if (isOjpCombination(combo)) {
        dispatch(
          ojpPlanQueryAction(combo, responseAction, routingError, options)
        )
      } else {
        const query = generateOtp2Query(combo)
        dispatch(
          createGraphQLQueryAction(
            query.query,
            query.variables,
            responseAction,
            routingError,
            options
          )
        )
      }
    })
    // Update OTP URL params if a new search. In other words, if we're
    // performing a search based on query params taken from the URL after a back
    // button press, we don't need to update the OTP URL.
    // TODO: For old searches that we are re-running, should we be **replacing**
    //  the URL params here (instead of **pushing** a new path to history like
    //  what currently happens in updateOtpUrlParams)? That way we could ensure
    //  that the path absolutely accurately reflects the app state.
    const params = getUrlParams()
    if (isNewSearch || params.ui_activeSearch !== searchId) {
      dispatch(updateOtpUrlParams(state, searchId))
    }

    return RoutingQueryCallResult.SUCCESS
  }
}

export default {
  ...vendorActions,
  fetchNearby,
  findStopTimesForStop,
  routingQuery
}
