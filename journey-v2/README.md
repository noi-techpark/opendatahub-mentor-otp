<!--
SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>

SPDX-License-Identifier: MIT
-->

# Web app based on otp-react-redux

<img src="https://github.com/opentripplanner/otp-react-redux/raw/master/otprr.png" width="500" />

[![Join the chat for otp-react-redux at https://gitter.im/opentripplanner/otp-react-redux](https://badges.gitter.im/repo.png)](https://gitter.im/opentripplanner/otp-react-redux)

This is an example implementation using otp-react-redux for Italy South Tyrol region. otp-react-redux is a library for writing modern [OpenTripPlanner](http://www.opentripplanner.org/)-compatible multimodal journey planning applications using [React]() and [Redux]().

## Note on this integration

While it's not possible to directly use otp-react-redux as a library, we want to integrate as much upstream changes as possible. We've integrated the base otp-react-redux app as a git submodule in `otp-react-redux` folder.

Changes that are generic enough to be integrated within the upstream application should be done in the otp-react-redux git repository. Some files from this folder are copied over from otp-react-redux, mainly the dependendies of the project and the build scripts.

When updating the upstream repository, the dependencies and potential changes to the build script should be integrated in this folder as needed.

## Running the application

To run, first clone the repo and install [yarn](https://yarnpkg.com/) if needed.

Update `config.yml` with the needed API keys, and optionally, the OTP endpoint and initial map origin. (The default values are for a test server for Portland, OR.). See the comments at the head of the config file for further details.

Install the dependencies and start a local development server using the following script:

```bash
yarn start
```

The port on which the development server listens can be configured in `.env`.

Should you want to maintain multiple configuration files, OTP-RR can be made to use a custom config file by using environment variables. Other environment variables also exist. `CUSTOM_CSS` can be used to point to a css file to inject, and `JS_CONFIG` can be used to point to a `config.js` file to override the one shipped with OTP-RR.

```bash
env YAML_CONFIG=/absolute/path/to/config.yml yarn start
```

## Deploying the UI

Build a production js/css bundle by running `yarn build`. The build will appear in the `dist/` directory). It consists entirely of static files and can be served by a simple static web server or CDN.

The same environment variables which affect the behavior of `yarn start` also affect `yarn build`. Running the following command builds OTP-RR with customized js and css:

```bash
env JS_CONFIG=my-custom-js.js CUSTOM_CSS=my-custom-css.css yarn build
```

## Library Documentation

You can chat with the main OTP-RR developers in our [Gitter chat](https://gitter.im/opentripplanner/otp-react-redux). Support is not guaranteed, but we may be able to answer questions and assist people wishing to make contributions. 

As of version 2.0, otp-react-redux utilizes React's context API in a number of components. This changed the way that some components receive props such that they will not work properly unless wrapped with the context provider used in the `ResponsiveWebapp` component.

## Content integration

Additional comments regarding current layers content integrated in the demo app

### Parking

OTP doesn't provide Bike parking separately, nor parking with real-time or availability information at the moment. This information isn't rendered for the user.

    