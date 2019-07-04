#!/usr/bin/env bash

skip_wait=$1

cd $(dirname $0)

PID_FILE=run/play.pid

if [ -f $PID_FILE ] ; then
    PID=$(cat $PID_FILE)
    kill $PID
    if [ "$?" == "0" ] ; then
       echo "Kill signal sent"
    else
       echo "Some error occured "
    fi
else
   echo "$PID_FILE not found - possibly application not running"
fi

if [ "$skip_wait" == "-s" ] ; then
    echo "Skip waiting for shutdown due to -s option"
    exit 0
fi

echo -n "Waiting for SMG to stop listening: "
while curl -sf localhost:9000/config/status >/dev/null 2>&1 ; do echo -n . ; sleep 1 ; done ; echo down

if [ -f $PID_FILE ] ; then
    echo -n "Waiting for pid file do disappear (use kill -9 $PID and then rm $PID_FILE to force): "
    while test -f $PID_FILE ; do
        echo -n .
        sleep 1
    done
    echo gone
fi

echo "Done"
