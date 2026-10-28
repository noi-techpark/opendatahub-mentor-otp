#!/bin/sh
# SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
#
# SPDX-License-Identifier: CC0-1.0

# Serves /var/otp/graph-<id>.obj, <id> being this OTP's serialization version, which OTP fills into
# build-config.json's graph entry. Without that graph OTP exits and never turns healthy.

# /var/otp is a volume seeded from the image only at creation, so the image's configs reach a
# running container here, on every start.
install -C -m644 /etc/otp/build-config.json /etc/otp/router-config.json /etc/otp/otp-config.json /var/otp/

exec java $JAVA_OPTS -cp @/app/jib-classpath-file @/app/jib-main-class-file /var/otp/ --load --serve
