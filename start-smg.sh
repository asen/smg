#!/usr/bin/env bash

WAIT_OPT="--wait"
WAIT=""
if [ "$1" == "$WAIT_OPT" ] ; then
  WAIT=$WAIT_OPT
  shift
fi

APP_HOME=`dirname $0`
JVM_MEM=${1:-"8g"}
HTTP_PORT=${2:-"9000"}
BIND_ADDRESS=$3
if [ "$BIND_ADDRESS" == "" ] ; then
  BIND_OPT=""
  JMX_BIND_OPT=""
  BIND_STR=""
else
  BIND_STR=" bind=$BIND_ADDRESS"
  BIND_OPT="-Dhttp.address=$BIND_ADDRESS"
  JMX_BIND_OPT="-J-Djava.rmi.server.hostname=$BIND_ADDRESS -J-Dcom.sun.management.jmxremote.host=$BIND_ADDRESS"
fi

cd $APP_HOME

PID_FILE=run/play.pid

if [ -f $PID_FILE ] ; then
    echo "Error: $PID_FILE exists. Please stop SMG or remove $PID_FILE manually if SMG is not running"
    exit 1
fi

ALT_CONF=/etc/smg/app.conf
if [ -f $ALT_CONF ] ; then
  echo -n "(Using $ALT_CONF) "
  APP_CONF="-Dconfig.file=$ALT_CONF"
else
  APP_CONF=""
fi

JMX_OPTS="$JMX_BIND_OPT -J-Dcom.sun.management.jmxremote.port=9001 \
    -J-Dcom.sun.management.jmxremote.ssl=false \
    -J-Dcom.sun.management.jmxremote.authenticate=false"

#GC_OPTS="-J-XX:+UseParallelGC"
GC_OPTS=""

# Needed to be able to talk to kubernetes cluster: https://github.com/kubernetes-client/java/issues/893
JAVA_11_KUBE_TLS_OPT="-J-Djdk.tls.client.protocols=TLSv1.2"

NOHUP=nohup
LOGGER_OPT=""
if [ "$WAIT" == "$WAIT_OPT" ] ; then
  LOGGER_OPT="-Dlogger.resource=logback-play-stdout.xml"
  NOHUP=""
  _term() {
    echo "SMG: Caught SIGTERM signal!"
    ./stop-smg.sh
    echo "SMG: Exiting gracefully"
    exit 0
  }
  trap _term SIGTERM
fi

COMMAND="bin/smg $APP_CONF -J-Xmx$JVM_MEM $GC_OPTS $JMX_OPTS $JAVA_11_KUBE_TLS_OPT $LOGGER_OPT \
    -Dplay.crypto.secret=fabe980f8f27865e11eeaf9e4ff4fc65 \
    -Dhttp.port=$HTTP_PORT $BIND_OPT \
    -Dakka.http.parsing.max-uri-length=2m \
    -Dakka.http.parsing.max-header-value-length=2m \
    -Dplay.server.akka.max-header-value-length=2m \
    -Dpidfile.path=run/play.pid"

if [ "$WAIT" == "$WAIT_OPT" ] ; then
  $COMMAND &
  ret=$?
  child=$!
    if [ "$ret" == "0" ] ; then
    echo "Started (mem=$JVM_MEM port=$HTTP_PORT$BIND_STR)."
  else
    echo "Some error occurred ($ret)"
    exit $ret
  fi
  wait "$child"
else
  $NOHUP $COMMAND >logs/nohup.out 2>&1 &
  ret=$?
  if [ "$ret" == "0" ] ; then
    echo "Started (mem=$JVM_MEM port=$HTTP_PORT$BIND_STR). \
Check $APP_HOME/logs/nohup.out for errors and $APP_HOME/logs/application.log for progress"
  else
    echo "Some error occurred ($ret)"
    exit $ret
  fi
fi
