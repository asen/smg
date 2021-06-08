#!/bin/bash

exec /usr/bin/rrdcached -g -l unix:/var/rrdtool/rrdcached/rrdcached.sock -m 664 -b /var/rrdtool/rrdcached "$@"
