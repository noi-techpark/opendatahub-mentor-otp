FROM ghcr.io/mfdz/amarillo:latest

# Currently, we don't download gtfs, as we assume it's mounted at /data/latestGTFS.zip
# RUN apt-get update && apt-get install -y wget zip && rm -r /var/lib/apt/lists/*
# RUN wget -O latestGtfs.zip https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw

# Remove preexisting config
RUN rm /app/conf/region/* && \
    rm /app/conf/agency/*

# Overwrite config
COPY ./amarillo /app


# COPY ./infrastructure/docker/amarillo/docker-entrypoint.sh ./docker-entrypoint.sh
# RUN chmod +x ./docker-entrypoint.sh

# ENTRYPOINT ["./docker-entrypoint.sh"]