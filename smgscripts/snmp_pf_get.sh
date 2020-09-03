#!/usr/bin/env bash
set -e

CACHE_FILE=$1
if [ "$CACHE_FILE" == "" ] ; then
  echo "Usage $0 <cache_file> <oid1> [<oid2>...]"
fi
CACHE_DATA=`cat $CACHE_FILE`

shift

for v in "$@" ; do grep -E ":$v\\s+=" <<< "$CACHE_DATA" ; done | sed -e 's/^.*:\s//g' | while read line ;
do
  # do stuff with $line
  case $line in
    *\ kB)
    # echo asen:$line
    numkb=`echo $line | sed 's/[^0-9]*//g'`
    echo "$((1024 * $numkb))"
    ;;
    No\ Such\ Instance*)
    echo "No Such Instance returned" 1>&2
    exit 1
    ;;
    *)
    echo $line
    ;;
  esac
done
