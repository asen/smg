#!/usr/bin/env bash
set -e

HOST=$1
shift

COMMUNITY=${1:-public}


snmpwalk -v2c -c$COMMUNITY -mall $ADDOPTS $HOST hrStorage
snmpwalk -v2c -c$COMMUNITY -mall $ADDOPTS $HOST if
