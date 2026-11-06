#!/bin/bash -e

# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

set -a && source .env && set +a

# The base OTP the Dockerfile names, unless .env names another.
OTP_IMAGE="${OTP_IMAGE:-$(sed -n 's/^ARG BASE_IMAGE=//p' "$(dirname "$0")/infrastructure/docker/otp/Dockerfile")}"

docker run \
  -it \
  -v .:/var/opentripplanner/:z \
  --rm \
  -e JAVA_TOOL_OPTIONS="-Xmx6G" \
  -p 8080:8080 \
  ${OTP_IMAGE} --load --serve
