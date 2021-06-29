#!/usr/bin/env bash

export JAVA_OPTS="-XX:+UseParallelGC -XX:+ExitOnOutOfMemoryError -Dcom.sun.management.jmxremote.port=9001 \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dakka.http.parsing.max-uri-length=2m \
    -Dakka.http.parsing.max-header-value-length=2m \
    -Dplay.server.akka.max-header-value-length=2m \
    -Djdk.tls.client.protocols=TLSv1.2 \
    "

exec sbt run
