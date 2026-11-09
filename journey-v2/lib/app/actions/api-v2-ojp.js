// Replaces the vendor `actions/apiV2` module (see the NormalModuleReplacementPlugin
// in craco.config.js), so the OTP GraphQL calls can be moved to the OJP services
// one at a time without modifying the otp-react-redux submodule.
//
// Every action not overridden here is the vendor one. The import below uses the
// alias on purpose: the replacement only matches relative requests to apiV2,
// so this one still resolves to the original vendor file.
import * as OJP from 'ojp-sdk'
import { toDate } from 'date-fns-tz'
import vendorActions from '@otp-react-redux/lib/actions/apiV2'

import {
  assembleBasePath,
  createQueryAction,
  fetchingStopTimesForStop,
  findStopTimesForStopError,
  findStopTimesForStopResponse
} from '@otp-react-redux/lib/actions/api'
import { FETCH_STATUS } from '@otp-react-redux/lib/util/constants'
import { getServiceStart } from '@otp-react-redux/lib/util/api'
import { routeIsValid } from '@otp-react-redux/lib/util/viewer'

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
 * Reads all the departures of a stop for one service day (03:30 to 03:30 of
 * the next day, the same window the vendor GraphQL query covers) from the
 * OJP StopEventRequest, one page at a time: each page starts from the last
 * departure of the previous one, and the repeated departures are dropped.
 */
async function fetchOjpStopEvents(sdk, stopRef, serviceStart) {
  const serviceEnd = serviceStart.getTime() + 24 * 3600 * 1000
  const stopEvents = []
  const seen = new Set()
  let pageStart = serviceStart

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

export default {
  ...vendorActions,
  findStopTimesForStop
}
