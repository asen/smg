#!/usr/bin/env bash

HOST=$1
if [ "$HOST" == "" ] ; then
  echo "Usage $0 <host>"
fi

SNMP_VALS=`dirname $0`/snmp_vals.sh

read avail cached buff totl <<<$($SNMP_VALS $HOST memAvailReal.0 memCached.0 memBuffer.0 memTotalReal.0)

let "in = 100 - ($avail * 100 / $totl)"
let "out = 100 - (($avail + $cached + $buff ) * 100 / $totl)"

echo $in
echo $out
