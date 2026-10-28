# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# The apt list below is Ubuntu noble's, so the base tag names the distro.
FROM docker.io/library/eclipse-temurin:25-jdk-noble

# JDK 25 comes from the base image: the pipeline's drivers are source-launched under JEP 458 and
# have no build step. docker.io is here for the CLI, which otp-docker.sh uses over the mounted
# socket, and docker-compose-v2 for otp-rollout.sh. make drives the pipeline; gzip verifies the
# gzipped feeds before they are kept.
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates \
    cron \
    curl \
    docker.io \
    docker-compose-v2 \
    gzip \
    make \
    nginx \
    osmium-tool \
    pyosmium \
    python3 \
    unzip \
    zip \
    && rm -rf /var/lib/apt/lists/*

# /build and /graph are volumes at run time, so nothing may be baked there.
WORKDIR /app
COPY build-graph.sh .
COPY build-config.json otp-config.json router-config.json ./
COPY infrastructure/docker/otp-graph-build/otp-docker.sh .
COPY infrastructure/docker/otp-graph-build/run-build.sh .
COPY infrastructure/docker/otp-graph-build/otp-rollout.sh .
RUN chmod +x build-graph.sh otp-docker.sh run-build.sh otp-rollout.sh

ARG DOCKER_ROLLOUT_VERSION=v0.14
ARG DOCKER_ROLLOUT_SHA256=cdeaba6ae9eee3b0b606286e585bbda6787283d801a6ad6d9b9d2bc347fda05b
RUN mkdir -p /usr/local/lib/docker/cli-plugins \
    && curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-rollout \
      "https://raw.githubusercontent.com/wowu/docker-rollout/${DOCKER_ROLLOUT_VERSION}/docker-rollout" \
    && echo "${DOCKER_ROLLOUT_SHA256}  /usr/local/lib/docker/cli-plugins/docker-rollout" | sha256sum -c - \
    && chmod +x /usr/local/lib/docker/cli-plugins/docker-rollout

# The pipeline, vendored whole: its Makefile derives scripts/ and geo/ from its own directory.
COPY pipeline/ /pipeline/

ENV PIPELINE_DIR=/pipeline \
    OTP_DOCKER=/app/otp-docker.sh \
    WORK=/build

# The pipeline's state directories, pointed at the work volume, for a `make -C /pipeline` run by
# hand in this container: the Makefile assigns them with ?=, so the environment redirects them away
# from /pipeline on the container's writable layer. build-graph.sh passes the same values on the
# command line, which outranks this.
ENV INPUT_DIR=/build/input \
  DATA_DIR=/build/data \
  STATE_DIR=/build/state \
  OTP_DIR=/build/graph \
  TOOLKIT_JAR=/build/netex-toolkit-shaded.jar

# Everything the pipeline and its drivers reach for on PATH. A base image or package list that
# stops providing one fails this build.
RUN for c in java make curl python3 unzip zip gzip osmium pyosmium-up-to-date docker flock; do \
      command -v "$c" >/dev/null || { echo "missing from PATH: $c" >&2; exit 1; }; \
    done \
    && docker compose version && docker rollout --version

# nginx: serve build artifacts from /build for debugging (directory listing only)
RUN echo 'server {\n\
    listen 80;\n\
    root /build;\n\
    autoindex on;\n\
    autoindex_exact_size off;\n\
    autoindex_localtime on;\n\
    location / { try_files $uri $uri/ =404; }\n\
    location ~* \\.log$ { types { } default_type text/plain; }\n\
}' > /etc/nginx/sites-available/default \
    && rm -f /etc/nginx/sites-enabled/default \
    && ln -s /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default

COPY infrastructure/docker/otp-graph-build/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
