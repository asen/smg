#!/usr/bin/env bash

INTERVAL=${1:-"60"}
HOST=${2:-"localhost:9000"}

echo $HOST/run/$INTERVAL
curl -s -S -X POST http://$HOST/run/$INTERVAL
echo

