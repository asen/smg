#!/usr/bin/env bash

APP_HOME=`dirname $0`
JVM_MEM=${1:-"8g"}

cd $APP_HOME

ALT_CONF=/etc/smg/app.conf
if [ -f $ALT_CONF ] ; then
  echo -n "(Using $ALT_CONF) "
  APP_CONF="-Dconfig.file=$ALT_CONF"
else
  APP_CONF=""
fi

JMX_OPTS="-J-Dcom.sun.management.jmxremote.port=9001 \
    -J-Dcom.sun.management.jmxremote.ssl=false \
    -J-Dcom.sun.management.jmxremote.authenticate=false"

nohup bin/smg $APP_CONF -J-Xmx$JVM_MEM $JMX_OPTS \
    -Dplay.crypto.secret=fabe980f8f27865e11eeaf9e4ff4fc65 \
    -Dpidfile.path=run/play.pid \
    >logs/nohup.out 2>&1 &

if [ "$?" == "0" ] ; then
   echo "Started. Check $APP_HOME/logs/application.log"
else
   echo "Some error occured"
fi

