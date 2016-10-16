#!/usr/bin/env bash

#Target[<%= @node_name %>__.swappercent]: 100 - (( memAvailSwap.0&memAvailSwap.0:ops@<%= @node_ipaddr %> ) * 100 / ( memTotalSwap.0&memTotalSwap.0:ops@<%= @node_ipaddr %> ))
#Title[<%= @node_name %>__.swappercent]: <%= @node_name %>__: Swap Used
#PageTop[<%= @node_name %>__.swappercent]: <h1><%= @node_name %>__: Swap Used</h1>
#  <p><a href="/mrtg-cf/ng/index.html?fn=<%= @node_name %>__.swappercent&cfg=<%= @cfg_file %>">Highchart (2 weeks graph)
#  </a></p>
#MaxBytes[<%= @node_name %>__.swappercent]: 100
#ShortLegend[<%= @node_name %>__.swappercent]: %
#YLegend[<%= @node_name %>__.swappercent]: Swap %
#LegendI[<%= @node_name %>__.swappercent]: Used
#LegendO[<%= @node_name %>__.swappercent]:
#Legend1[<%= @node_name %>__.swappercent]: Percentage of total swap used
#Legend2[<%= @node_name %>__.swappercent]:
#Options[<%= @node_name %>__.swappercent]: growright,gauge,transparent,nopercent
#Unscaled[<%= @node_name %>__.swappercent]: ymwd

HOST=$1
if [ "$HOST" == "" ] ; then
  echo "Usage $0 <host>"
fi

SNMP_VALS=`dirname $0`/snmp_vals.sh

read avail totl <<<$($SNMP_VALS $HOST memAvailSwap.0 memTotalSwap.0)

let "in = 100 - ($avail * 100 / $totl)"

echo $in
echo $in

