#!/usr/bin/env bash

INTERVAL=${1:-"60"}
TREEID="$2"
HOST=${3:-"localhost:9000"}

if [ "$TREEID" == "" ] ; then
  URL="http://$HOST/run/$INTERVAL"
else
  URL="http://$HOST/run/$INTERVAL?id=$TREEID"
fi

echo "Using URL=$URL"
curl -s -S -X POST "$URL"
echo

