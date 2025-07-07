# until https://github.com/mobidata-bw/x2gbfs/pull/259 is merged we use an image from Leonard's repo
# afterwards we go back to upstream
FROM ghcr.io/leonardehrenfried/x2gbfs:2025-07-07T11-20

ARG CARSHARING_BASEURL=https://carsharing.otp.opendatahub.testingmachine.eu

ENV CARSHARING_BASEURL=$CARSHARING_BASEURL

# create a "fake" cron job with sleep 60 and endless while loop
ENTRYPOINT while true; do python -m x2gbfs.x2gbfs -p noi -b ${CARSHARING_BASEURL}; sleep 60; done;

