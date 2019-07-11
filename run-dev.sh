#!/usr/bin/env bash

export JAVA_OPTS="-XX:+UseParallelGC -Dcom.sun.management.jmxremote.port=9001 \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dakka.http.parsing.max-uri-length=2m \
    -Dplay.server.netty.maxHeaderSize=65535"

exec sbt run
