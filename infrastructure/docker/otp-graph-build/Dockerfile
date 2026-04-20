# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

FROM ubuntu:24.04

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    wget \
    zip \
    unzip \
    curl \
    nginx \
    cron \
    ca-certificates \
    default-jre-headless \
    osmium-tool \
    pyosmium \
    docker.io \
    # for Switzerland Netex conversion:
    git \
    && rm -rf /var/lib/apt/lists/*

# Copy build script and all static config files it depends on
WORKDIR /build
COPY build-graph.sh .
COPY switzerland-south-tyrol.geojson .
COPY transform-scheduled-stop-point-ids.xsl .
COPY build-config.json .
COPY otp-config.json .
COPY router-config.json .
COPY infrastructure/docker/otp-graph-build/run-build.sh .
RUN chmod +x build-graph.sh run-build.sh

# Switzerland Netex build
COPY build-switzerland-netex.sh .
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
RUN ln -s /root/.local/bin/uv /usr/local/bin/uv

# nginx: serve build artifacts from /graph for debugging (directory listing only)
RUN echo 'server {\n\
    listen 80;\n\
    root /graph;\n\
    autoindex on;\n\
    autoindex_exact_size off;\n\
    autoindex_localtime on;\n\
    location / { try_files $uri $uri/ =404; }\n\
}' > /etc/nginx/sites-available/default \
    && rm -f /etc/nginx/sites-enabled/default \
    && ln -s /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default

COPY infrastructure/docker/otp-graph-build/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]
