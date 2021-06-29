#!/bin/bash

mode=${1:-"smg"}
echo "/run-entrypoint.sh: starting with mode=$mode"

case $mode in
  smg)
    echo "/run-entrypoint.sh: running smg"
    shift
    exec /start-smg.sh --wait "$@"
    ;;
  rrdcached)
    echo "/run-entrypoint.sh: running rrdcached"
    shift
    exec /run-rrdcached.sh "$@"
    ;;
  nginx)
    echo "/run-entrypoint.sh: running nginx"
    shift
    exec /run-nginx.sh "$@"
    ;;
  inotify)
    echo "/run-entrypoint.sh: running inotify-reload"
    shift
    exec /run-inotify-reload.sh "$@"
    ;;
  *)
    echo "run-entrypoint.sh: running custom command: $1"
    exec "$@"
    ;;
esac
