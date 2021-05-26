#!/usr/bin/env bash

if [ "1" == "--help" ] ; then
  echo "Usage: $0 <inp_file>"
  echo "Usage: curl <host:port>>/nginx_status | $0"
  echo "The main purpose of this script is to convert the nginx stub status module output"
  echo "to a more conveinet for parsing otput. Example output from nginx:"
  echo
  echo "    Active connections: 75"
  echo "    server accepts handled requests"
  echo "     350302 350302 320320"
  echo "    Reading: 61 Writing: 16 Waiting: 14"
  echo
  echo "Is converted to:"
  echo
  echo "    active: 75"
  echo "    accepts: 350302"
  echo "    handled: 350302"
  echo "    requests: 320320"
  echo "    reading: 61"
  echo "    writing: 16"
  echo "    waiting: 14"
  echo
  exit 1
fi

inp_file=${1:-"-"}
out=`cat $inp_file`
out_line=$(tr '\n' ',' <<< "$out")
#echo out_line="$out_line"
SAVE_IFS="$IFS"
IFS=","
read -r line1 line2 line3 line4 <<< "$out_line"
IFS="$SAVE_IFS"

#echo line1=$line1
#echo line2=$line2
#echo line3=$line3
#echo line4=$line4
#line1=Active connections: 69
#line2=server accepts handled requests
#line3= 348783 348783 310818
#line4=Reading: 0 Writing: 50 Waiting: 19
read blah blah aconn <<< "$line1"
read accepts handled requests <<< "$line3"
read blah reading blah writing blah waiting <<< "$line4"
echo "active: $aconn"
echo "accepts: $accepts"
echo "handled: $handled"
echo "requests: $requests"
echo "reading: $reading"
echo "writing: $writing"
echo "waiting: $waiting"

