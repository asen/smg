#!/usr/bin/env bash

export JAVA_OPTS="-Dcom.sun.management.jmxremote.port=9001 \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false"

exec ./activator run
