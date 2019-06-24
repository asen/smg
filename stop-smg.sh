#!/usr/bin/env bash

cd $(dirname $0)

PID_FILE=run/play.pid
PID=$(cat $PID_FILE)
kill $PID

if [ "$?" == "0" ] ; then
   echo "Kill signal sent"
else
   echo "Some error occured - possibly application not running"
fi

echo -n "Waiting for SMG to shut down cleanly (use kill -9 $PID to force): "
while curl -sf localhost:9000/config/status >/dev/null 2>&1 ; do echo -n . ; sleep 1 ; done ; echo down
