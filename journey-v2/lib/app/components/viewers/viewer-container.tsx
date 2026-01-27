// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import { connect } from 'react-redux'
import React, { HTMLAttributes } from 'react'

import { AppReduxState } from '@otp-react-redux/lib/util/state-types'
import { MainPanelContent } from '@otp-react-redux/lib/actions/ui-constants'

import NoiNearbyView from './nearby/noi-nearby-view'
import PatternViewer from '@otp-react-redux/lib/components/viewers/pattern-viewer'
import RouteViewer from '@otp-react-redux/lib/components/viewers/route-viewer'
import StopScheduleViewer from '@otp-react-redux/lib/components/viewers/stop-schedule-viewer'
import TripViewer from '@otp-react-redux/lib/components/viewers/trip-viewer'
import PoiViewer from '../poi-viewer'

interface Props extends HTMLAttributes<HTMLDivElement> {
  isViewingStop: boolean
  mainPanelContent: number
}

const ViewerContainer = ({
  children,
  className,
  isViewingStop,
  mainPanelContent,
  style
}: Props) => {
  // check for main panel content
  switch (mainPanelContent) {
    case MainPanelContent.ROUTE_VIEWER:
      return <RouteViewer hideBackButton />
    case MainPanelContent.PATTERN_VIEWER:
      return <PatternViewer hideBackButton />
    case MainPanelContent.TRIP_VIEWER:
      return <TripViewer hideBackButton />
    case MainPanelContent.NEARBY_VIEW:
      return <NoiNearbyView  />
    default:
      // check for stop viewer
      if (isViewingStop) {
        return <StopScheduleViewer hideBackButton />
      }

      // otherwise, return default content
      return (
        <div className={className} style={style}>
          {children}
        </div>
      )
  }
}

// connect to the redux store

const mapStateToProps = (state: AppReduxState) => {
  const { mainPanelContent, viewedStop } = state.otp.ui
  return {
    isViewingStop: !!viewedStop,
    mainPanelContent
  }
}

export default connect(mapStateToProps)(ViewerContainer)
