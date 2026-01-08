FROM node:20.18.0-alpine AS build

WORKDIR /build
COPY ./web-demo .

RUN mkdir -p /run/nginx
RUN mkdir -p /var/www/html

# needed by yarn install for some reason
RUN apk add --no-cache git

RUN yarn install
RUN yarn build

FROM nginx:1.27.2-alpine
# be quiet
ENV NGINX_ENTRYPOINT_QUIET_LOGS=1
COPY --from=build /build/dist/* /usr/share/nginx/html

