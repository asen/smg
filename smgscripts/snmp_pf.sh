#!/usr/bin/env bash
set -e

COMMUNITY=$1
shift

HOST=$1
shift

CACHE_FILE=$1
if [ "$CACHE_FILE" == "" ] ; then
  echo "Usage $0 <community> <host> <cache_file> <oid1> [<oid2>...]"
fi
shift

if [[ "$1" == "-*" ]] ; then
    ADDOPTS="$1"
    shift
else
    ADDOPTS=""
fi

mkdir -p `dirname $CACHE_FILE`
snmpget -v2c -c$COMMUNITY -mall $ADDOPTS $HOST "$@" > $CACHE_FILE

