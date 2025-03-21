// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

/* eslint-disable prettier/prettier */
/* eslint-disable sort-imports-es6-autofix/sort-imports-es6 */
import DateTimeModal from '@otp-react-redux/lib/components/form/date-time-modal'
import DateTimePreview from '@otp-react-redux/lib/components/form/date-time-preview'
import ErrorMessage from '@otp-react-redux/lib/components/form/error-message'
import LocationField from '@otp-react-redux/lib/components/form/connected-location-field'
import PlanTripButton from '@otp-react-redux/lib/components/form/plan-trip-button'
import SwitchButton from '@otp-react-redux/lib/components/form/switch-button'
import DefaultMap from './components/map/default-map'
import Map from './components/map/map'
import DefaultItinerary from '@otp-react-redux/lib/components/narrative/default/default-itinerary'
import MetroItinerary from '@otp-react-redux/lib/components/narrative/metro/metro-itinerary'
import NarrativeItineraries from '@otp-react-redux/lib/components/narrative/narrative-itineraries'
import NarrativeItinerary from '@otp-react-redux/lib/components/narrative/narrative-itinerary'
import RealtimeAnnotation from '@otp-react-redux/lib/components/narrative/realtime-annotation'
import SimpleRealtimeAnnotation from '@otp-react-redux/lib/components/narrative/simple-realtime-annotation'
import TransportationNetworkCompanyLeg from '@otp-react-redux/lib/components/narrative/default/tnc-leg'
import TripDetails from '@otp-react-redux/lib/components/narrative/connected-trip-details'
import TripTools from '@otp-react-redux/lib/components/narrative/trip-tools'
import MobileMain from '@otp-react-redux/lib/components/mobile/main'
import NavLoginButton from '@otp-react-redux/lib/components/user/nav-login-button'
import NavLoginButtonAuth0 from '@otp-react-redux/lib/components/user/nav-login-button-auth0'
import StopScheduleViewer from '@otp-react-redux/lib/components/viewers/stop-schedule-viewer'
import ViewStopButton from '@otp-react-redux/lib/components/viewers/view-stop-button'
import ViewerContainer from '@otp-react-redux/lib/components/viewers/viewer-container'
// import ResponsiveWebapp from '@otp-react-redux/lib/components/app/responsive-webapp'
import AppMenu from '@otp-react-redux/lib/components/app/app-menu'
import DesktopNav from '@otp-react-redux/lib/components/app/desktop-nav'
import BatchResultsScreen from '@otp-react-redux/lib/components/mobile/batch-results-screen'
import BatchSearchScreen from '@otp-react-redux/lib/components/mobile/batch-search-screen'
import FormattedMode from '@otp-react-redux/lib/components/util/formatted-mode'
import { setAutoPlan } from '@otp-react-redux/lib/actions/config'
import { getCurrentPosition } from '@otp-react-redux/lib/actions/location'
import { clearLocation } from '@otp-react-redux/lib/actions/form'
import { setLocationToCurrent, setMapCenter } from '@otp-react-redux/lib/actions/map'
import { findNearbyStops } from '@otp-react-redux/lib/actions/api'
import createCallTakerReducer from '@otp-react-redux/lib/reducers/call-taker'
import createOtpReducer from '@otp-react-redux/lib/reducers/create-otp-reducer'
import createUserReducer from '@otp-react-redux/lib/reducers/create-user-reducer'
import otpUtils from '@otp-react-redux/lib/util'

import BatchRoutingPanel from '@otp-react-redux/lib/components/app/batch-routing-panel'
import NoiResponsiveWebapp from './components/app/noi-responsive-webapp'
import RoutingPanel from './components/app/routing-panel'
import DestinationPanel from './components/app/destination-panel'

// Example NOI specific component
import ExampleComponent from './components/example'


// TODO: Remove this when we fix the configs for calltaker.
const MobileResultsScreen = BatchResultsScreen
const MobileSearchScreen = BatchSearchScreen
export {
  // form components
  DateTimeModal,
  DateTimePreview,
  ErrorMessage,
  LocationField,
  PlanTripButton,
  SwitchButton,

  // map components
  DefaultMap,
  Map,

  // narrative components
  DefaultItinerary,
  MetroItinerary,
  NarrativeItineraries,
  NarrativeItinerary,
  RealtimeAnnotation,
  SimpleRealtimeAnnotation,
  TransportationNetworkCompanyLeg,
  TripDetails,
  TripTools,

  // mobile components
  MobileMain,
  MobileResultsScreen,
  MobileSearchScreen,

  // viewer components
  StopScheduleViewer,
  ViewerContainer,
  ViewStopButton,

  // user-related components
  NavLoginButton,
  NavLoginButtonAuth0,

  // app components,
  // ResponsiveWebapp,
  AppMenu,
  DesktopNav,
  NoiResponsiveWebapp,

  // batch routing components
  BatchResultsScreen,
  BatchRoutingPanel,
  BatchSearchScreen,
  RoutingPanel,
  DestinationPanel,

  // Util components
  FormattedMode,

  // actions
  clearLocation,
  findNearbyStops,
  getCurrentPosition,
  setAutoPlan,
  setLocationToCurrent,
  setMapCenter,

  // redux utilities
  createCallTakerReducer,
  createOtpReducer,
  createUserReducer,

  // general utilities
  otpUtils
}
