#!/usr/bin/env bash

# RCPTS=$1 - all args are passed on to mail
SEVERITY=$SMG_ALERT_SEVERITY #-$2  # one of  RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, FAILED, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED
ALERT_KEY=$SMG_ALERT_KEY #$3
SUBJ=$SMG_ALERT_SUBJECT #$4
BODY=$SMG_ALERT_BODY #$5


#echo "Would be sending e-mail to $RCPTS"
#echo "Subject: $SEVERITY $SUBJ"
#echo
#echo $BODY

echo "$BODY" | mail -s "[SMG] $SUBJ" "$@"

