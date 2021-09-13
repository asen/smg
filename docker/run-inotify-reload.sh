#!/bin/bash

SMG_RELOAD_HOST=${SMG_RELOAD_HOST:-localhost}
SMG_RELOAD_PORT=${SMG_RELOAD_PORT:-9000}
SMG_RELOAD_CMD="curl -sS -f -X POST http://${SMG_RELOAD_HOST}:${SMG_RELOAD_PORT}/reload"
SMG_DIRS_TO_WATCH=${SMG_DIRS_TO_WATCH:-"/etc/smg/conf.d"}
SMG_RELOAD_BACKOFF=${SMG_RELOAD_BACKOFF:-1}
SMG_INIT_PAUSE=${SMG_INIT_PAUSE:-10}

INOTIFY_WAIT="inotifywait -e modify -e moved_to -e moved_from -e move -e create -e delete -r"

_term() {
  echo "INOTIFY_WAIT: [`date`] Caught SIGTERM signal!"
  for cpid in `pgrep -P $$` ; do
    echo "INOTIFY_WAIT: [`date`] Sending SIGTERM signal to child $cpid"
    kill $cpid
  done
  echo "INOTIFY_WAIT: [`date`] Exiting gracefully"
  exit 0
}
trap _term SIGTERM SIGINT

echo "INOTIFY_WAIT: [`date`] Sleeping for $SMG_INIT_PAUSE seconds before starting to watch $SMG_DIRS_TO_WATCH"
sleep $SMG_INIT_PAUSE
echo "INOTIFY_WAIT: [`date`] Entering infinite loop ..."

while true ; do
  $INOTIFY_WAIT $SMG_DIRS_TO_WATCH
  ret=$?
  if [ "$ret" == "0" ] ; then
    echo "INOTIFY_WAIT: [`date`] Changes detected, reloading conf"
    sleep $SMG_RELOAD_BACKOFF
    $SMG_RELOAD_CMD
  fi
done
echo "INOTIFY_WAIT: [`date`] Exiting (after loop)..." # never?
