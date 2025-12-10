// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later

import PropTypes from 'prop-types'
import React, { Component } from 'react'
import { Navbar, Nav } from 'react-bootstrap'
import FontAwesome from 'react-fontawesome'
import { connect } from 'react-redux'

import { setMobileScreen } from '../../actions/ui'
import AppMenu from '../app/app-menu'
import NavLoginButtonAuth0 from '../../components/user/nav-login-button-auth0'
import { accountLinks, getAuth0Config } from '../../util/auth'

import interreg from '../../images/interreg.png'
import openmove from '../../images/openmove.png'
import merano from "../../images/merano.png";
import bolzano from "../../images/ComuneBolzano.png";
import opendatahub from "../../images/opendatahub.png"

const logos = {
  interreg,
  openmove,
  bolzano,
  merano
}

class MobileNavigationBar extends Component {
  static propTypes = {
    backScreen: PropTypes.number,
    headerAction: PropTypes.element,
    headerText: PropTypes.string,
    showBackButton: PropTypes.bool,
    setMobileScreen: PropTypes.func,
    title: PropTypes.element
  }

  _backButtonPressed = () => {
    const { backScreen, onBackClicked } = this.props
    if (backScreen) this.props.setMobileScreen(this.props.backScreen)
    else if (typeof onBackClicked === 'function') onBackClicked()
  }

  render () {

    const {
      config,
      auth0Config,
      headerAction,
      headerText,
      showBackButton,
      title
    } = this.props

    const {brandByDomain} = config;
    let {branding, brandNavbar, brandNavbarLogo} = config;

    //TODO switch by domain location.hostname

    let brandLogo = null;

    if (brandByDomain && (location.hostname in brandByDomain)) {
      branding = brandByDomain[ location.hostname ]['branding'];
      brandNavbar = brandByDomain[ location.hostname ]['brandNavbar'];
      brandNavbarLogo = brandByDomain[ location.hostname ]['brandNavbarLogo'];
      brandLogo = logos[ brandNavbarLogo ] || null;
    }

    return (
      <Navbar fluid fixedTop collapseOnSelect>
        <Navbar.Header>
        {/* { brandLogo &&
            <img className='brandLogo' src={brandLogo} />
          } */}
          <Navbar.Brand>
            {
              showBackButton &&
                <>
                  <Navbar.Link href="#" onClick={this._backButtonPressed}>
                    <FontAwesome name='arrow-left' tag="i" />
                  </Navbar.Link>

                </>
            }
            {config.brandNavbar} <span>BETA</span>
          </Navbar.Brand>
          <Navbar.Toggle />
        </Navbar.Header>

        <Navbar.Collapse>
          <Navbar.Text>
            { headerText || title }
          </Navbar.Text>
          {/* <Nav pullRight>
            <AppMenu /> */}
          <Nav>
            <AppMenu />
          </Nav>
          <Nav className="navbar-logo-wrapper">
            <img className='odhLogo' src={opendatahub} alt="Open Data Hub"/>
          </Nav>
        </Navbar.Collapse>

        {/**
          * HACK: Normally, NavLoginButtonAuth0 should be inside a <Nav> element,
          * however, in mobile mode, react-bootstrap's <Nav> causes the
          * submenus of this component to be displayed full-screen-width,
          * and that behavior is not desired here.
          */}
        {auth0Config && (
          <NavLoginButtonAuth0
            id='login-control'
            links={accountLinks}
          />
        )}
      </Navbar>
    )
  }
}

// connect to the redux store

const mapStateToProps = (state, ownProps) => {
  return {
    auth0Config: getAuth0Config(state.otp.config.persistence),
    config: state.otp.config
  }
}

const mapDispatchToProps = {
  setMobileScreen
}

export default connect(mapStateToProps, mapDispatchToProps)(MobileNavigationBar)
