#!/usr/bin/env bash

SUBJ_PREFIX="[SMG]"
if [ "$1" == "--sprefix" ] ; then
  shift
  SUBJ_PREFIX="$1"
  shift
fi

# RCPTS=$1 - all remaining args are passed on to mail, including rcpts
SEVERITY=$SMG_ALERT_SEVERITY #-$2  # one of  RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, FAILED, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED
ALERT_KEY=$SMG_ALERT_KEY #$3
SUBJ=$SMG_ALERT_SUBJECT #$4
BODY=$SMG_ALERT_BODY #$5

MYTIME=`date +%H:%M:%S`
echo "$BODY" | mail -s "$SUBJ_PREFIX $SUBJ ($MYTIME)" "$@"
