ARG BASE_IMAGE=opentripplanner/opentripplanner:2.10.0_2026-08-17T09-22
FROM ${BASE_IMAGE}

WORKDIR /var/otp

ADD router-config.json otp-config.json /var/otp/

RUN apt-get update && apt-get install -y --no-install-recommends inotify-tools \
    && rm -rf /var/lib/apt/lists/*

COPY infrastructure/docker/otp/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
