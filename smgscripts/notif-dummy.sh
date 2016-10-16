#!/usr/bin/env bash

RCPTS=$1
SEVERITY=$2  # one of  RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, UNKNOWN, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED
ALERT_KEY=$3
SUBJ=$4
BODY=$5

echo "Would be sending e-mail to $RCPTS"
echo "Subject: $SEVERITY $SUBJ"
echo
echo $BODY

exit 0
