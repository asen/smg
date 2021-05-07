#!/usr/bin/env bash

INTERVAL=$1

if [ "$INTERVAL" == "" ] ; then
  echo "Usage: $0 <interval> [<tree_root>] [smg_host_port=localhost:9000]"
  echo "If no tree_root is provided (or if empty) it will trigger a full interval run"
  echo "If tree_root is provided it will only trigger running the supplied root command and any children"
  exit 1
fi

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

