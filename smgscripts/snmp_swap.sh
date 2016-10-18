#!/usr/bin/env bash

HOST=$1
if [ "$HOST" == "" ] ; then
  echo "Usage $0 <host>"
fi

SNMP_VALS=`dirname $0`/snmp_vals.sh

read avail totl <<<$($SNMP_VALS $HOST memAvailSwap.0 memTotalSwap.0)

let "in = 100 - ($avail * 100 / $totl)"

echo $in
echo $in

