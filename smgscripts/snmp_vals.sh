#!/usr/bin/env bash

COMMUNITY=public

HOST=$1
if [ "$HOST" == "" ] ; then
  echo "Usage $0 <host> <oid1> [<oid2>...]"
fi

shift

snmpget -v2c -c$COMMUNITY -mall -Ovq $HOST "$@" | while read line ;
do
  # do stuff with $line
  case $line in
    *\ kB)
    let "out=1024*`echo $line | cut -d ' ' -f 1`"
#    echo "KB: $line"
    echo $out
    ;;
    *)
    echo $line
    ;;
  esac
done
