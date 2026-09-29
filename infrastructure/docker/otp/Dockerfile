ARG BASE_IMAGE=docker.io/lehrenfried/opentripplanner:stop-place-psa
FROM ${BASE_IMAGE}

WORKDIR /var/otp

# Docker seeds /var/otp from the image only when the volume is first created, so the configs land
# in /etc/otp and entrypoint.sh installs them into /var/otp on every start.
COPY router-config.json otp-config.json /etc/otp/

RUN apt-get update && apt-get install -y --no-install-recommends inotify-tools \
    && rm -rf /var/lib/apt/lists/*

COPY infrastructure/docker/otp/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
