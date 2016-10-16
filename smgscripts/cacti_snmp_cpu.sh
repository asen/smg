#!/usr/bin/env bash

HOST=$1
if [ "$HOST" == "" ] ; then
  echo "Usage $0 <host>"
fi

SSH_OPTS="-o StrictHostKeyChecking=no"

ssh $SSH_OPTS root@$HOST "cat /proc/stat | grep 'cpu '" | sed 's/cpu //g' | xargs -n 1 echo


