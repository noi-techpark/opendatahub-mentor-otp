# until https://github.com/mobidata-bw/x2gbfs/pull/259 is merged we use an image from Leonard's repo
# afterwards we go back to upstream
FROM ghcr.io/leonardehrenfried/x2gbfs:2025-07-07T11-20

ARG CARSHARING_BASEURL=https://carsharing.otp.opendatahub.testingmachine.eu

ENV CARSHARING_BASEURL=$CARSHARING_BASEURL

COPY ./carsharing/entrypoint.sh .

ENTRYPOINT sh entrypoint.sh

