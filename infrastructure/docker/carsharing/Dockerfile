FROM ghcr.io/mobidata-bw/x2gbfs:2025-07-14T12-29

ARG CARSHARING_BASEURL=https://carsharing.otp.opendatahub.testingmachine.eu

ENV CARSHARING_BASEURL=$CARSHARING_BASEURL

COPY ./carsharing/entrypoint.sh .

ENTRYPOINT sh entrypoint.sh

