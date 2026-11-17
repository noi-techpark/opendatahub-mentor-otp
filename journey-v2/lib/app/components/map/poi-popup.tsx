// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later

import { FormattedMessage } from 'react-intl'
import React, { ReactNode } from 'react'

import NoiFromToPicker from '../viewers/nearby/noi-from-to-picker'

type Props = {
  children?: ReactNode
  icon: ReactNode
  onViewMore?: () => void
  place: any
  title: ReactNode
}

// Map popup card from the Figma tooltip (Mobile > Tab trip, bus stop): icon and
// name on top, the main information in bold, an optional "View more" link and
// the start/arrive actions. Shared by every POI popup on the map so they all
// look the same; styled in index.scss (.poi-popup).
const PoiPopup = ({ children, icon, onViewMore, place, title }: Props) => (
  <div className="poi-popup">
    <div className="poi-popup__header">
      {icon}
      <span>{title}</span>
    </div>
    {children && <div className="poi-popup__main">{children}</div>}
    {onViewMore && (
      <button className="poi-popup__link" onClick={onViewMore} type="button">
        <FormattedMessage id="components.RouteDetails.moreDetails" />
      </button>
    )}
    <div className="poi-popup__actions">
      <NoiFromToPicker place={place} />
    </div>
  </div>
)

// The vector tiles send `routes` either as JSON ([{"shortName":"201"}, ...])
// or, for longer lists, as a compact table: a "[13]{gtfsType:string?,mode:string,
// shortName:string}" header and then one comma separated row per route.
// Returns the distinct short names, e.g. "201, 211, 6".
export const getRouteNames = (routes?: string): string => {
  if (!routes) return ''
  let list: Record<string, string | null>[] = []
  try {
    if (routes.trim().startsWith('[{') || routes.trim() === '[]') {
      list = JSON.parse(routes)
    } else {
      const [head, ...rows] = routes.trim().split('\n')
      const fields = head
        .slice(head.indexOf('{') + 1, head.lastIndexOf('}'))
        .split(',')
        .map((field) => field.split(':')[0])
      list = rows.map((row) =>
        Object.fromEntries(
          row
            .split(',')
            .map((value, i) => [fields[i], value === 'null' ? null : value])
        )
      )
    }
  } catch (e) {
    console.warn('Unreadable routes in map tile', routes, e)
    return ''
  }
  const names = list.map((route) => route.shortName).filter(Boolean)
  return Array.from(new Set(names)).join(', ')
}

export default PoiPopup
