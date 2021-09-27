#!/bin/bash

SLEEP=${SMG_RRDCACHED_SLEEP_ON_TERM:-5}

_term() {
    echo "SMG-rrdcached: Caught SIGTERM signal! Sleeping for $SLEEP seconds to allow SMG to shutdown first"
    sleep "$SLEEP"
    kill "$child"
    wait "$child"
    echo "SMG-rrdcached: Exiting gracefully"
    exit 0
}

/usr/bin/rrdcached -g -l unix:/var/rrdtool/rrdcached/rrdcached.sock -m 664 -b /var/rrdtool/rrdcached "$@" &
ret=$?
child=$!
if [ "$ret" == "0" ] ; then
    echo "SMG-rrdcached: Started."
else
    echo "SMG-rrdcached: Some error occurred ($ret)"
    exit $ret
fi

trap _term SIGTERM

wait "$child"
