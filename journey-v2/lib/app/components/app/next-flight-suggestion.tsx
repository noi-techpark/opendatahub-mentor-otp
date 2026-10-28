// SPDX-FileCopyrightText: 2026 NOI <digital@noi.bz.it>
//
// SPDX-License-Identifier: MIT

import { connect } from 'react-redux'
import { FormattedMessage, useIntl } from 'react-intl'
import { formatInTimeZone } from 'date-fns-tz'
import React, { useEffect, useState } from 'react'

import { assembleBasePath, routingQuery } from '@otp-react-redux/lib/actions/api'
import { getActiveSearch } from '@otp-react-redux/lib/util/state'
import { setQueryParam } from '@otp-react-redux/lib/actions/form'

const LOOKAHEAD_SECONDS = 30 * 24 * 60 * 60
const MAX_SUGGESTIONS = 3

const NEXT_DEPARTURES_QUERY = `query NextFlights($stopId: String!, $startTime: Long!, $timeRange: Int!) {
  stop(id: $stopId) {
    stoptimesForPatterns(startTime: $startTime, timeRange: $timeRange, numberOfDepartures: ${MAX_SUGGESTIONS}) {
      pattern {
        route { mode shortName }
        stops { gtfsId }
      }
      stoptimes { serviceDay scheduledDeparture }
    }
  }
}`

type StopLocation = { id?: string; name?: string; type?: string }

type Flight = { departure: number; route: string }

type Props = {
  config: any
  from?: StopLocation
  noResults: boolean
  setQueryParam: (payload: Record<string, string>) => void
  routingQuery: () => void
  timeZone: string
  to?: StopLocation
}

const isStop = (loc?: StopLocation): loc is Required<StopLocation> =>
  loc?.type === 'stop' && !!loc.id

async function fetchNextFlights(
  url: string,
  fromId: string,
  toId: string
): Promise<Flight[]> {
  const response = await fetch(url, {
    body: JSON.stringify({
      query: NEXT_DEPARTURES_QUERY,
      variables: {
        startTime: Math.floor(Date.now() / 1000),
        stopId: fromId,
        timeRange: LOOKAHEAD_SECONDS
      }
    }),
    headers: { 'Content-Type': 'application/json' },
    method: 'POST'
  })
  const json = await response.json()
  const patterns = json?.data?.stop?.stoptimesForPatterns || []
  const flights: Flight[] = []
  patterns.forEach(({ pattern, stoptimes }: any) => {
    if (pattern?.route?.mode !== 'AIRPLANE') return
    const stopIds: string[] = pattern.stops.map((s: any) => s.gtfsId)
    // The destination must come after the origin, otherwise it's the return flight.
    if (stopIds.indexOf(toId) <= stopIds.indexOf(fromId)) return
    stoptimes.forEach((st: any) =>
      flights.push({
        departure: (st.serviceDay + st.scheduledDeparture) * 1000,
        route: pattern.route.shortName
      })
    )
  })
  return flights
    .sort((a, b) => a.departure - b.departure)
    .slice(0, MAX_SUGGESTIONS)
}

/**
 * When a search between two stops finds no itineraries, suggests the next
 * direct flights between them so the user can re-run the search at that time.
 */
const NextFlightSuggestion = ({
  config,
  from,
  noResults,
  routingQuery,
  setQueryParam,
  timeZone,
  to
}: Props) => {
  const intl = useIntl()
  const [flights, setFlights] = useState<Flight[]>([])
  const fromId = isStop(from) ? from.id : null
  const toId = isStop(to) ? to.id : null

  useEffect(() => {
    setFlights([])
    if (!noResults || !fromId || !toId) return
    let cancelled = false
    const url = `${assembleBasePath(config)}${
      config.api?.basePath ?? '/otp'
    }/gtfs/v1`
    fetchNextFlights(url, fromId, toId)
      .then((result) => !cancelled && setFlights(result))
      .catch(() => !cancelled && setFlights([]))
    return () => {
      cancelled = true
    }
  }, [noResults, fromId, toId, config])

  if (!flights.length) return null

  const searchAt = (departure: number) => {
    setQueryParam({
      date: formatInTimeZone(departure, timeZone, 'yyyy-MM-dd'),
      departArrive: 'DEPART',
      time: formatInTimeZone(departure, timeZone, 'HH:mm')
    })
    routingQuery()
  }

  return (
    <div className="next-flight-suggestion" style={{ padding: '10px' }}>
      <strong>
        <FormattedMessage id="components.NextFlightSuggestion.header" />
      </strong>
      <p>
        <FormattedMessage
          id="components.NextFlightSuggestion.body"
          values={{ from: from?.name, to: to?.name }}
        />
      </p>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {flights.map((flight) => (
          <li key={`${flight.route}-${flight.departure}`}>
            <button
              className="btn btn-link"
              onClick={() => searchAt(flight.departure)}
              type="button"
            >
              {flight.route} ·{' '}
              {intl.formatDate(flight.departure, {
                day: 'numeric',
                month: 'short',
                timeZone,
                weekday: 'short'
              })}
              ,{' '}
              {intl.formatTime(flight.departure, {
                hour: '2-digit',
                hourCycle: 'h23',
                minute: '2-digit',
                timeZone
              })}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

const mapStateToProps = (state: any) => {
  const search = getActiveSearch(state)
  const responses: any[] = search?.response || []
  const noResults =
    !!search &&
    !search.pending &&
    responses.length > 0 &&
    responses.every((res) => !res?.plan?.itineraries?.length)
  return {
    config: state.otp.config,
    from: search?.query?.from,
    noResults,
    timeZone: state.otp.config.homeTimezone || 'Europe/Rome',
    to: search?.query?.to
  }
}

const mapDispatchToProps = { routingQuery, setQueryParam }

export default connect(mapStateToProps, mapDispatchToProps)(NextFlightSuggestion)
