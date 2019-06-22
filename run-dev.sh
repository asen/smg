#!/usr/bin/env bash

export JAVA_OPTS="-Dcom.sun.management.jmxremote.port=9001 \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dplay.server.netty.maxInitialLineLength=65535 \
    -Dplay.server.netty.maxHeaderSize=65535"

exec sbt run
