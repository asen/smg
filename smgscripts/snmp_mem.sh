#!/usr/bin/env bash

#Target[<%= @node_name %>__.mempercent]: 100 - (( memAvailReal.0&memAvailReal.0:ops@<%= @node_ipaddr %> + PseudoZero&memCached.0:ops@<%= @node_ipaddr %> + PseudoZero&memBuffer.0:ops@<%= @node_ipaddr %> ) * 100 / ( memTotalReal.0&memTotalReal.0:ops@<%= @node_ipaddr %> ))
#Title[<%= @node_name %>__.mempercent]: <%= @node_name %>__: Memory Used
#PageTop[<%= @node_name %>__.mempercent]: <h1><%= @node_name %>__: Memory Used</h1>
#  <p><a href="/mrtg-cf/ng/index.html?fn=<%= @node_name %>__.mempercent&cfg=<%= @cfg_file %>">Highchart (2 weeks graph)
#  </a></p>
#MaxBytes[<%= @node_name %>__.mempercent]: 100
#ShortLegend[<%= @node_name %>__.mempercent]: %
#YLegend[<%= @node_name %>__.mempercent]: Memory %
#LegendI[<%= @node_name %>__.mempercent]: Used
#LegendO[<%= @node_name %>__.mempercent]: Used - Buffers/Cache
#Legend1[<%= @node_name %>__.mempercent]: Percentage of total memory used
#Legend2[<%= @node_name %>__.mempercent]: Percentage of total memory used - buffers/cache
#Options[<%= @node_name %>__.mempercent]: growright,gauge,transparent,nopercent
#Unscaled[<%= @node_name %>__.mempercent]: ymwd

HOST=$1
if [ "$HOST" == "" ] ; then
  echo "Usage $0 <host>"
fi

SNMP_VALS=`dirname $0`/snmp_vals.sh

read avail cached buff totl <<<$($SNMP_VALS $HOST memAvailReal.0 memCached.0 memBuffer.0 memTotalReal.0)

let "in = 100 - ($avail * 100 / $totl)"
let "out = 100 - (($avail + $cached + $buff ) * 100 / $totl)"

echo $in
echo $out
