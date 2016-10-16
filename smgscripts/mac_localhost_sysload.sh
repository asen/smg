#!/usr/bin/env bash

uptime | sed 's/^.*averages: //' | awk '{ print $1 ; print $2 ; print $3}'
