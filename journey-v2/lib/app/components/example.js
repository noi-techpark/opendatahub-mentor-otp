// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

/* eslint-disable react/prop-types */
// TODO: Typescript (config object)
import { connect } from 'react-redux'
import React, { Component } from 'react'

class ExampleComponent extends Component {
  constructor() {
    super()
    this.state = {
      activeViewIndex: 0
    }
  }

  render() {
    return (
      <div>Hello World</div>
    )
  }
}

// Connect to Redux store

const mapStateToProps = (state, ownProps) => {
  return {
  }
}

const mapDispatchToProps = {
}

export default connect(mapStateToProps, mapDispatchToProps)(ExampleComponent)
