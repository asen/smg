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
