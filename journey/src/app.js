// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later

// import React/Redux libraries
import React, { Component } from 'react'
import { withNamespaces } from "react-i18next";
// import Bootstrap Grid components for layout
import { Navbar, Grid, Row, Col, Nav } from 'react-bootstrap'
// import OTP-RR components
import DefaultMainPanel from './components/app/default-main-panel'
import LineItinerary from './components/narrative/line-itin/line-itinerary'
import Map from './components/map/map'
import MobileMain from './components/mobile/main'
import ResponsiveWebapp from './components/app/responsive-webapp'
import AppMenu from './components/app/app-menu'

import i18n from './i18n'

import { setMapCenter, setMapZoom } from './actions/config'

import mergeDeep from './util/mergeDeep'

import interreg from './images/interreg.png'
import openmove from './images/openmove.png'
import merano from './images/merano.png'
import bolzano from './images/ComuneBolzano.png'
import opendatahub from './images/opendatahub.png'

import MatomoIntegration from './util/matomo-integration';


const logos = {
  interreg,
  openmove,
  merano,
  bolzano
}
// Loads a yaml config file which is set in the webpack.config.js file. This
// setting is defined from a custom environment setting passed into webpack or
// defaults to ./config.yml
let otpConfig = require(YAML_CONFIG);
const {configByDomain} = otpConfig;
const {brandByDomain} = otpConfig;

if (configByDomain && (location.hostname in configByDomain)) {
  const domainConfig = configByDomain[ location.hostname ];

  if( domainConfig.map?.initLat && domainConfig.map?.initLon ) {
    otpConfig.map.initLat = Number(domainConfig.map.initLat);
    otpConfig.map.initLon = Number(domainConfig.map.initLon);
  }
  if( domainConfig.map?.initZoom ) {
    otpConfig.map.initZoom = Number(domainConfig.map.initZoom);
  }
}

console.log('OTP_CONFIG', otpConfig);

// Loads a JavaScript file which is set in the webpack.config.js file. This
// setting is defined from a custom environment setting passed into webpack or
// defaults to ./config.js
const jsConfig = require(JS_CONFIG).configure(otpConfig);



window.OM = {
  config: otpConfig,
  YAML_CONFIG
};

const {getItineraryFooter, LegIcon, ModeIcon} = jsConfig

if (!LegIcon || !ModeIcon) {
  throw new Error('LegIcon and ModeIcon must be defined in config.js')
}
class JourneyWebapp extends Component {

  render () {
    const { t } = this.props;
    const {brandByDomain} = otpConfig;
    let {branding, brandNavbar, brandNavbarLogo} = otpConfig;

    let brandLogo = null;

    if (brandByDomain && (location.hostname in brandByDomain)) {
      branding = brandByDomain[ location.hostname ]['branding'];
      brandNavbar = brandByDomain[ location.hostname ]['brandNavbar'];
      brandNavbarLogo = brandByDomain[ location.hostname ]['brandNavbarLogo'];
      brandLogo = logos[ brandNavbarLogo ] || null;
      setTimeout(()=> {
        document.title = brandByDomain[ location.hostname ]['title'] || ''
      });
    }

    /** desktop view **/
    const desktopView = (
      <div className='otp'>
        <Navbar fluid collapseOnSelect fixedTop>
          <Navbar.Header>
          {/* { brandLogo &&
            <img className='brandLogo' src={brandLogo} />
          } */}
            <Navbar.Brand> {brandNavbar} <span>BETA</span></Navbar.Brand>
            <Navbar.Toggle />
          </Navbar.Header>
          <Navbar.Collapse>
            {/* <Nav pullRight>
              <AppMenu /> */}
            <Nav>
              <AppMenu />
            </Nav>
            <Nav pullRight>
              <a href='https://opendatahub.com/' target='_blank' rel='noopener noreferrer'>
                <img className='odhLogo' src={opendatahub} alt='Open Data Hub' />
              </a>
            </Nav>
          </Navbar.Collapse>
        </Navbar>

        <div className='main-container'>
          <div className='sidebar'>
            {/* <main> is needed for accessibility checks. */}
            <main>
              <DefaultMainPanel
                itineraryClass={LineItinerary}
                itineraryFooter={getItineraryFooter(t)}
                LegIcon={LegIcon}
                ModeIcon={ModeIcon}
              />
            </main>
          </div>
          <div className='map-container'>
            <Map mapConfig={otpConfig.map}/>
          </div>
        </div>
      </div>
    )

    /** mobile view **/
    const mobileView = (
      // <main> is needed for accessibility checks.
      <main>
        <MobileMain
          map={(<Map mapConfig={otpConfig.map}/>)}
          itineraryClass={LineItinerary}
          itineraryFooter={getItineraryFooter(t)}
          LegIcon={LegIcon}
          ModeIcon={ModeIcon}
        />
      </main>
    )

    /** the main webapp **/
    return (
      <>
        <ResponsiveWebapp
          desktopView={desktopView}
          // Pass the LegIcon here for use in the print view.
          LegIcon={LegIcon}
          mobileView={mobileView}
        />
        <MatomoIntegration t={t} />
      </>
    )
  }
}

export default withNamespaces()(JourneyWebapp);