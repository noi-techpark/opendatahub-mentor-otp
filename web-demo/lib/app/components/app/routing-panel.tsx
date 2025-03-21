// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import { connect } from 'react-redux'
import { FormattedMessage, injectIntl, IntlShape } from 'react-intl'
import React, { Component, FormEvent } from 'react'

import { getActiveSearch, getShowUserSettings } from '@otp-react-redux/lib/util/state'
import { getPersistenceMode } from '@otp-react-redux/lib/util/user'
import BatchSettings from '@otp-react-redux/lib/components/form/batch-settings'
import InvisibleA11yLabel from '@otp-react-redux/lib/components/util/invisible-a11y-label'
import LocationField from '@otp-react-redux/lib/components/form/connected-location-field'
import NarrativeItineraries from '@otp-react-redux/lib/components/narrative/narrative-itineraries'
import SwitchButton from '@otp-react-redux/lib/components/form/switch-button'
import UserSettings from '@otp-react-redux/lib/components/form/user-settings'
import ViewerContainer from '@otp-react-redux/lib/components/viewers/viewer-container'
import { Button } from '@otp-react-redux/lib/components/form/batch-styled'
import NoiNearbyView from '../viewers/nearby/noi-nearby-view'

interface Props {
  activeSearch: any
  intl: IntlShape
  mobile?: boolean
  showUserSettings: boolean
}

/**
 * Main panel for the batch/trip comparison form.
 */
class RoutingPanel extends Component<Props> {
  state = {
    planTripClicked: false
  }
  step = 0

  handleSubmit = (e: FormEvent) => e.preventDefault()

  handlePlanTripClick = () => {
    this.setState({ planTripClicked: true })
  }

  goToStep = (s: number) => {
    // this.setState({ step: s })
    this.step = s;
    console.log(this.step)
  }

  renderDestination = () => {
    const { intl, mobile } = this.props
    const { planTripClicked } = this.state
    const mapAction = mobile
    return (
      <ViewerContainer
        className="batch-routing-panel"
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
            <div className="switch-button-container">
              <SwitchButton />
            </div>
          </span>
          <BatchSettings onPlanTripClick={this.handlePlanTripClick} />
        </form>
        <form
            className="form"
            onSubmit={this.goToStep(1)}
            style={{ padding: '10px' }}>
          <Button type="submit"> Next </Button>
        </form>

        <NoiNearbyView />
        <div
          className="desktop-narrative-container"
          style={{
            flexGrow: 1,
            overflowY: 'hidden'
          }}
        >
          <NarrativeItineraries />
        </div>
      </ViewerContainer>
    )
  }

  renderRouting = () => {
    const { intl, mobile } = this.props
    const { planTripClicked } = this.state
    const mapAction = mobile
    return (
      <ViewerContainer
        className="batch-routing-panel"
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
            <div className="switch-button-container">
              <SwitchButton />
            </div>
          </span>
          <BatchSettings onPlanTripClick={this.handlePlanTripClick} />
        </form>
        <form
            className="form"
            onSubmit={this.goToStep(0)}
            style={{ padding: '10px' }}>
          <Button> Back </Button>
        </form>

        <div
          className="desktop-narrative-container"
          style={{
            flexGrow: 1,
            overflowY: 'hidden'
          }}
        >
          <NarrativeItineraries />
        </div>
      </ViewerContainer>
    )
  }

  render() {
    return this.step == 0 ? this.renderDestination() : this.renderRouting()
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
    activeSearch: getActiveSearch(state),
    showUserSettings,
    currentQuery: state.otp.currentQuery
  }
}

export default connect(mapStateToProps)(injectIntl(RoutingPanel))
