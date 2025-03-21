// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import { connect } from 'react-redux'
import { FormattedMessage, injectIntl, IntlShape } from 'react-intl'
import React, { Component, FormEvent } from 'react'

import {
  getActiveSearch,
  getShowUserSettings
} from '@otp-react-redux/lib/util/state'
import { getPersistenceMode } from '@otp-react-redux/lib/util/user'
import InvisibleA11yLabel from '@otp-react-redux/lib/components/util/invisible-a11y-label'
import LocationField from '@otp-react-redux/lib/components/form/connected-location-field'
import ViewerContainer from '@otp-react-redux/lib/components/viewers/viewer-container'
import { Button } from '@otp-react-redux/lib/components/form/batch-styled'

import BatchSettings from '@otp-react-redux/lib/components/form/batch-settings'
import NarrativeItineraries from '@otp-react-redux/lib/components/narrative/narrative-itineraries'
import SwitchButton from '@otp-react-redux/lib/components/form/switch-button'

import UserSettings from '@otp-react-redux/lib/components/form/user-settings'

import PoiViewer from '../poi-viewer'

import * as apiActions from '@otp-react-redux/lib/actions/api'
import * as mapActions from '@otp-react-redux/lib/actions/map'
import * as uiActions from '@otp-react-redux/lib/actions/ui'
import {
  MainPanelContent,
  MobileScreens
} from '@otp-react-redux/lib/actions/ui-constants'

import NoiNearbyView from '../viewers/nearby/noi-nearby-view'

interface Props {
  query: any
  activeSearch: any
  intl: IntlShape
  mobile?: boolean
  showUserSettings: boolean
}

/**
 * Main panel for the batch/trip comparison form.
 */
class DestinationPanel extends Component<Props> {
  state = {
    planTripClicked: false
  }

  handleSubmit = (e: FormEvent) => e.preventDefault()

  handlePlanTripClick = () => {
    this.setState({ planTripClicked: true })
  }

  render() {
    const { activeSearch, intl, mobile, query, showUserSettings } = this.props
    const { planTripClicked } = this.state

    let validLocationsStop = ['Stop', 'RentalVehicle', 'VehicleParking', 'BikeRentalStation'];
    let validLocationsPoi = ['Stop', 'RentalVehicle', 'VehicleParking', 'BikeRentalStation'];

    const mapAction = mobile
      ? intl.formatMessage({
          id: 'common.searchForms.tap'
        })
      : intl.formatMessage({
          id: 'common.searchForms.click'
        })

    return (
      <ViewerContainer
        className="destination-panel"
        style={{
          display: 'flex',
          flexDirection: 'column',
          height: '100%'
        }}
      >
        <InvisibleA11yLabel>
          <h1>
            <FormattedMessage id="components.BatchSearchScreen.header" />
          </h1>
        </InvisibleA11yLabel>
        <form
          className="form"
          onSubmit={this.handleSubmit}
          style={{ padding: '10px' }}
        >
          <span className="batch-routing-panel-location-fields">
            {(planTripClicked || query.from) && (
              <LocationField
                inputPlaceholder={intl.formatMessage(
                  { id: 'common.searchForms.enterStartLocation' },
                  { mapAction }
                )}
                isRequired
                locationType="from"
                selfValidate={planTripClicked}
                showClearButton={!mobile}
              />
            )}
            {(planTripClicked || query.from) && (
              <LocationField
                inputPlaceholder={intl.formatMessage(
                  { id: 'common.searchForms.enterDestination' },
                  { mapAction }
                )}
                isRequired
                locationType="to"
                selfValidate={planTripClicked}
                showClearButton={!mobile}
              />
            )}
            {(planTripClicked || query.from) && (
              <div className="switch-button-container">
                <SwitchButton />
              </div>
            )}
          </span>
          {(planTripClicked || query.from) && (
            <BatchSettings onPlanTripClick={this.handlePlanTripClick} />
          )}
        </form>
        {!(planTripClicked || query.from) && query.to && (<div>
          <PoiViewer
            handlePlanTripClick={this.handlePlanTripClick}
            hideBackButton
            selectedPlace={query.to}
          />
        </div>)}
        {/* !activeSearch && showUserSettings && (
          <UserSettings style={{ margin: '0 10px', overflowY: 'auto' }} />
        ) */}
        {activeSearch && (
          <div
            className="desktop-narrative-container"
            style={{
              flexGrow: 1,
              overflowY: 'hidden'
            }}
          >
            <NarrativeItineraries />
          </div>
        )}
        {!(planTripClicked || query.from) &&
          <NoiNearbyView
            handlePlanTripClick={this.handlePlanTripClick}
            validLocations={query.to.rawGeocodedFeature?.properties?.layer === 'stops' ? validLocationsStop : validLocationsPoi }
          />}
      </ViewerContainer>
    )
  }
}

// connect to the redux store
const mapStateToProps = (state: any) => {
  // Show the place shortcuts for OTP-middleware users who have accepted the terms of use
  // and deployments using persistence to localStorage. Don't show shortcuts otherwise.
  const showUserSettings =
    getShowUserSettings(state) &&
    (state.user.loggedInUser?.hasConsentedToTerms ||
      getPersistenceMode(state.otp.config.persistence).isLocalStorage)
  return {
    showUserSettings,
    query: state.otp.currentQuery,
    activeSearch: getActiveSearch(state)
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

export default connect(
  mapStateToProps,
  mapDispatchToProps
)(injectIntl(DestinationPanel))
