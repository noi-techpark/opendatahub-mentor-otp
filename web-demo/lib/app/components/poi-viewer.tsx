import { Alert, Button } from 'react-bootstrap'
import { Calendar } from '@styled-icons/fa-solid'

import { ArrowLeft } from '@styled-icons/fa-solid/ArrowLeft'
import { connect } from 'react-redux'
import { ExclamationCircle } from '@styled-icons/fa-solid/ExclamationCircle'
import { format, parse } from 'date-fns'
import { FormattedMessage, injectIntl, IntlShape } from 'react-intl'
import { MagnifyingGlass } from '@styled-icons/fa-solid/MagnifyingGlass'
import { MapRef } from 'react-map-gl'
import { utcToZonedTime } from 'date-fns-tz'
import coreUtils from '@opentripplanner/core-utils'
import React, { Component, FormEvent, useEffect } from 'react'
import styled from 'styled-components'

import * as apiActions from '@otp-react-redux/lib/actions/api'
import * as mapActions from '@otp-react-redux/lib/actions/map'
import { AppReduxState } from '@otp-react-redux/lib/util/state-types'
import { IconWithText } from '@otp-react-redux/lib/components/util/styledIcon'
import { isBlank, navigateBack } from '@otp-react-redux/lib/util/ui'
import {
  StopData,
  ZoomToPlaceHandler
} from '@otp-react-redux/lib/components/util/types'
import { stopIsFlex } from '@otp-react-redux/lib/util/viewer'
import { TransitOperatorConfig } from '@otp-react-redux/lib/util/config-types'
import PageTitle from '@otp-react-redux/lib/components/util/page-title'
import ServiceTimeRangeRetriever from '@otp-react-redux/lib/components/util/service-time-range-retriever'
import withMap from '@otp-react-redux/lib/components/map/with-map'

import {
  CardBody,
  CardHeader,
  CardTitle
} from '@otp-react-redux/lib/components/viewers/nearby/styled'
import FavoriteStopToggle from '@otp-react-redux/lib/components/viewers/favorite-stop-toggle'
import StopCardHeader from '@otp-react-redux/lib/components/viewers/nearby/stop-card-header'
import StopScheduleTable from '@otp-react-redux/lib/components/viewers/stop-schedule-table'
import TimezoneWarning from '@otp-react-redux/lib/components/viewers/timezone-warning'
import { P } from '@styled-icons/fa-solid'

import NoiFromToPicker from './viewers/nearby/noi-from-to-picker'

interface Props {
  calendarMax: string
  calendarMin: string
  handlePlanTripClick: () => void
  findStopTimesForStop: (arg: { date: string; stopId: string }) => void
  hideBackButton?: boolean
  homeTimezone: string
  intl: IntlShape
  map?: MapRef
  selectedPlace?: any
  showBlockIds?: boolean
  stopData?: StopData
  stopId?: string
  transitIndex: any
  transitOperators: TransitOperatorConfig[]
  zoomToPlace: ZoomToPlaceHandler
}

interface State {
  date: string,
  poiId: string | null,
  poiData: any | null
}

const { getCurrentDate, getUserTimezone } = coreUtils.time

/** The native date format used with <input type="date" /> elements */
const inputDateFormat = 'yyyy-MM-dd'

function getDefaultState(timeZone: string) {
  return {
    // Compare dates/times in the stop viewer based on the agency's timezone.
    date: getCurrentDate(timeZone),
    poiData: null,
    poiId: null
  }
}

// A scrollable container for the contents of the stop viewer body.
const Scrollable = styled.div`
  margin-right: -12px;
  overflow-y: auto;
  padding-right: 12px;
`
// Alert with custom styles
const StyledAlert = styled(Alert)`
  /* 'clear: both' prevents the date selector from overlapping with the alert. */
  clear: both;
  margin: 10px 0;
  padding: 5px 10px;
  text-align: center;
`

const HeaderCard = styled.div`
  display: flex;
  flex-direction: column;
  margin: 5px 0 0;

  ${CardBody} {
    margin: 25px 0 0;
  }

  input[type='date'] {
    background: inherit;
    border: none;
    clear: right;
    cursor: pointer;
    outline: none;
    width: 125px;
  }
  /* Remove arrows on date input */
  input[type='date']::-webkit-inner-spin-button {
    -webkit-appearance: none;
  }
  /* For Chromium browsers, remove extra space between date and the calendar icon. */
  input[type='date']::-webkit-calendar-picker-indicator {
    margin: 0;
  }
`

const StyledFromToPicker = styled(NoiFromToPicker)`
  button {
    color: inherit;
  }
  span {
    border-color: currentColor;
  }
  svg {
    color: inherit;
    fill: inherit;
  }
`

class PoiViewer extends Component<Props, State> {
  state = getDefaultState();

  constructor(props: Props) {
    super(props)
    let { selectedPlace, transitIndex } = props
    if (selectedPlace && selectedPlace.rawGeocodedFeature) {
      if (!selectedPlace.rawGeocodedFeature.properties) {
        let poiData = selectedPlace.rawGeocodedFeature;
        console.log(poiData);
        this.state = {
            date: getCurrentDate(props.homeTimezone),
            poiData,
            poiId: selectedPlace.rawGeocodedFeature.source_id
        };
      } else if (selectedPlace.rawGeocodedFeature.properties.source === 'otp') {
        let poiData = selectedPlace.rawGeocodedFeature.properties.addendum.stop;
        console.log(poiData);
        this.state = {
          date: getCurrentDate(props.homeTimezone),
          poiData,
          poiId: selectedPlace.rawGeocodedFeature.properties.source_id
        };
      } else {
        let poiData = selectedPlace.rawGeocodedFeature.properties;
        console.log(poiData);
        this.state = {
            date: getCurrentDate(props.homeTimezone),
            poiData,
            poiId: selectedPlace.rawGeocodedFeature.properties.source_id
        };
      }
    } else {
        this.state = getDefaultState(props.homeTimezone);
    }
  }

  componentDidUpdate(prevProps: Readonly<Props>, prevState: Readonly<State>, snapshot?: any): void {

    if(prevProps.selectedPlace !== this.props.selectedPlace) {
      let { selectedPlace, transitIndex } = this.props

      console.log("update", selectedPlace);
      if (selectedPlace && selectedPlace.rawGeocodedFeature) {
        if (!selectedPlace.rawGeocodedFeature.properties) {
          let poiData = selectedPlace.rawGeocodedFeature;
          console.log(poiData);
          this.setState({
              date: getCurrentDate(this.props.homeTimezone),
              poiData,
              poiId: selectedPlace.rawGeocodedFeature.source_id
          });
        } else if (selectedPlace.rawGeocodedFeature.properties.source === 'otp') {
          let poiData = selectedPlace.rawGeocodedFeature.properties.addendum.stop;
          console.log(poiData);
          this.setState({
            date: getCurrentDate(this.props.homeTimezone),
            poiData,
            poiId: selectedPlace.rawGeocodedFeature.properties.source_id
          });
        } else {
          let poiData = selectedPlace.rawGeocodedFeature.properties;
          console.log(poiData);
          this.setState({
              date: getCurrentDate(this.props.homeTimezone),
              poiData,
              poiId: selectedPlace.rawGeocodedFeature.properties.source_id
          });
        }
      } else if (selectedPlace && selectedPlace.properties) {
        let poiData = selectedPlace.properties;
        console.log("set state", poiData);
        this.setState({
            date: getCurrentDate(this.props.homeTimezone),
            poiData,
            poiId: selectedPlace.properties.id
        });
      } else {
          this.setState(getDefaultState(this.props.homeTimezone));
      }
    }

    if(prevState.poiData !== this.state.poiData) {
      console.log("New state", this.state);
      this._zoomToStop()
    }

    // FIXME: This is to prevent zooming the map back to entire itinerary
    // when accessing the schedule viewer from the nearby view.
  }

  _backClicked = () => navigateBack()

  componentDidMount() {
    this._findStopTimesForDate(this.state.date)
  }

  _findStopTimesForDate = (date: string) => {
    let { findStopTimesForStop, stopId } = this.props
    stopId = this.state.poiId
    if (stopId) {
      findStopTimesForStop({ date, stopId })
    }
  }

  getOperator = () => {
    let { stopData, transitOperators } = this.props
    stopData = this.state.poiData
    // We can use the first route, as this operator will only be used if there is only one operator
    return transitOperators.find(
      (o) => o.agencyId === stopData?.routes?.[0]?.agency.gtfsId
    )
  }

  /**
   * Gets a breadcrumbs-like title with format (operator stopcode/id | mode),
   * so we don't need to internationalize the title bar structure.
   */
  getTitle = () => {
    let { intl, stopData } = this.props
    stopData = this.state.poiData
    const operator = this.getOperator()
    return [
      (operator ? `${operator.name} ` : '') +
        intl.formatMessage(
          { id: 'components.StopViewer.titleBarStopId' },
          {
            stopId: stopData && coreUtils.itinerary.getDisplayedStopId(stopData)
          }
        ),
      // TODO: Rename string ids
      intl.formatMessage({ id: 'components.StopViewer.schedule' })
    ]
  }

  _isDateWithinRange = (date: string) => {
    const { calendarMax, calendarMin } = this.props
    // Date comparison is string-based (lexicographic).
    return !isBlank(date) && date >= calendarMin && date <= calendarMax
  }

  handleDateChange = (evt: FormEvent<HTMLInputElement>) => {
    // Check for non-empty date, and that date is within range before making request.
    // (Users can enter a date outside of the range using the Up/Down arrow keys in Firefox and Safari.)
    const date = (evt.target as HTMLInputElement).value
    if (this._isDateWithinRange(date)) {
      this._findStopTimesForDate(date)
    }
    this.setState({ date })
  }

  _zoomToStop = () => {
    const { map, zoomToPlace } = this.props
    const { poiData } = this.state
    console.log("Zooming to ", poiData);
    zoomToPlace(map, poiData)
  }

  _renderHeader = () => {
    const { hideBackButton, selectedPlace } = this.props
    const { poiData, poiId } = this.state;
    let stopData = poiData;
    let stopId = poiId;
    return (
      // CSS class stop-viewer-header is needed for customizing how logos are displayed.
      <div className="poi-viewer-header">
        {}
        {/* Back button */}
        {!hideBackButton && (
          <div className="back-button-container">
            <Button bsSize="small" onClick={this._backClicked}>
              <IconWithText Icon={ArrowLeft}>
                <FormattedMessage id="common.forms.back" />
              </IconWithText>
            </Button>
          </div>
        )}

        <HeaderCard>
          {stopData?.gtfsId ? (
            <StopCardHeader
            actionIcon={Calendar}
            // Remove entityId URL parameter when leaving nearby view.
            actionParams={{ entityId: undefined }}
            actionPath={`/schedule/${stopData.gtfsId}`}
            actionText={
                <FormattedMessage id="components.StopViewer.viewSchedule" />
            }

              // FIXME: What icon should we use?
              onZoomClick={this._zoomToStop}
              stopData={stopData}
              titleAs="h1"
            />
          ) : (
            <CardHeader>
                <CardTitle as="h1">{stopData?.name}</CardTitle>
            </CardHeader>
          )}
        </HeaderCard>
        {/*<FavoriteStopToggle stopData={stopData} />*/}

        <div style={{ clear: 'both' }} />
      </div>
    )
  }

  /**
   * Plan trip from/to here buttons, plus the schedule date control.
   */
  _renderControls = () => {
    const { calendarMax, calendarMin, homeTimezone, intl } =
      this.props
    const { date, poidData } = this.state
    let stopData = poidData;
    const inHomeTimezone = homeTimezone && homeTimezone === getUserTimezone()

    let warning
    if (!inHomeTimezone && this._isDateWithinRange(date)) {
      // Display a banner about the departure timezone if user's timezone is not the configured 'homeTimezone'
      // (e.g. cases where a user in New York looks at a schedule in Los Angeles).
      warning = (
        <StyledAlert bsStyle="info">
          <TimezoneWarning
            date={parse(date, inputDateFormat, new Date())}
            homeTimezone={homeTimezone}
          />
        </StyledAlert>
      )
    }

    if (!this._isDateWithinRange(date)) {
      warning = (
        <StyledAlert bsStyle="warning">
          <IconWithText Icon={ExclamationCircle}>
            <FormattedMessage id="components.StopViewer.noStopsFound" />
          </IconWithText>
        </StyledAlert>
      )
    }

    return (
      <div role="group" style={{ marginBottom: '10px' }}>
        {stopData ? <StyledFromToPicker place={stopData} /> : null}
        <input
          aria-label={intl.formatMessage({
            id: 'components.StopViewer.findSchedule'
          })}
          className="pull-right"
          max={calendarMax}
          min={calendarMin}
          onChange={this.handleDateChange}
          required
          type="date"
          value={this.state.date}
        />

        {warning}
      </div>
    )
  }

  render() {
    const { homeTimezone, showBlockIds, handlePlanTripClick } = this.props
    const { date, poiData } = this.state
    let stopData = poiData;

    return (
      <div className="stop-viewer base-color-bg">
        { }
        <PageTitle title={this.getTitle()} />
        <ServiceTimeRangeRetriever />
        {/* Header Block */}
        {this._renderHeader()}
        { /* JSON.stringify(stopData) */ }
        <div className="stop-viewer-body">
            <Button onClick={handlePlanTripClick}> Plan trip </Button>
        </div>
      </div>
    )
  }
}

// connect to redux store

const mapStateToProps = (state: AppReduxState) => {
  const {
    config,
    serviceTimeRange = { end: 0, start: 0 },
    transitIndex,
    ui
  } = state.otp
  const {
    homeTimezone,
    stopViewer: stopViewerConfig,
    transitOperators = [] as TransitOperatorConfig[]
  } = config
  const stopLookup = transitIndex.stops
  const stopId = ui.viewedStop?.stopId
  const stopData = stopLookup[stopId]
  const now = new Date()
  const thisYear = now.getFullYear()
  const { end, start } = serviceTimeRange
  // If start is not provided, default to the first day of the current calendar year in the user's timezone.
  // (No timezone conversion is needed in this case.)
  // If start is provided in OTP, convert that date in the agency's home time zone.
  const calendarMin = format(
    start
      ? utcToZonedTime(start * 1000, homeTimezone)
      : new Date(thisYear, 0, 1),
    inputDateFormat
  )
  // If end is not provided, default to the last day of the next calendar year in the user's timezone.
  // (No timezone conversion is needed in this case.)
  // If end date is provided and falls at midnight agency time,
  // use the previous second to get the last service day available.
  const calendarMax = format(
    end
      ? utcToZonedTime((end - 1) * 1000, homeTimezone)
      : new Date(thisYear + 1, 11, 31),
    inputDateFormat
  )

  return {
    calendarMax,
    calendarMin,
    homeTimezone,
    showBlockIds: stopViewerConfig?.showBlockIds,
    stopData,
    stopId,
    transitIndex,
    transitOperators
  }
}

const mapDispatchToProps = {
  findStopTimesForStop: apiActions.findStopTimesForStop,
  zoomToPlace: mapActions.zoomToPlace
}

export default injectIntl(
  withMap(connect(mapStateToProps, mapDispatchToProps)(PoiViewer))
)
