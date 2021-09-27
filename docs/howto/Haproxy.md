# How to monitor Haproxy Load Balancers

Since some point in time it is possible to build Haproxy with a built-in Prometheus exporter. SMG does not need that and can use the natife Haproxy "stats" end-point for monitoring.

I recommend exposing that on different than the default front-end port (and having it not accessible from the Internet), this is less important for "Internal" LBs which are not accessible from the Internet anyway.

Here is how the relevant haproxy.cfg section might look like:

    frontend stats_frontend
      bind *:8404
      stats enable
      stats uri /haproxy?stats
      # stats refresh 60s
      option dontlog-normal


This exposes the haproxy stats UI at the "http://%node_host%:8404/haproxy?stats" and the full stats would be accessible in CSV format at the "http://%node_host%:8404/haproxy?stats;csv" URL.

Once we are sure that Haproxy is configured to expose stats (by veryfying the above URLs) we are ready to configure SMG to monitor it.

The easiest way to achieve this is using the Autoconf plugin and the [haproxy template](https://github.com/asen/smg/blob/master/smgconf/ac-templates/haproxy.yml.ssp). We will need the hostname and/or the IP address of where our Haproxy instance is listening - lets assume a hostname of _haproxy1.host_ which can be resolved to an IP address using DNS.

We would create an autoconf target config file like this:

    cat > /opt/smg/data/conf/autoconf.d/haproxy1.host-haproxy.yml <<-EOH

    - output: haproxy1.host-haproxy-stats.yml
      template: haproxy
      node_name: haproxy1.host
      runtime_data: true
      runtime_data_timeout_sec: 15
      command: "curl -sS -f 'http://haproxy1.host:8404/haproxy?stats;csv'"
      context:
        filter_rxx: stats_frontend

    EOH

This will tell the autoconf plugin to use the output of the curl command and the haproxy template to generate actual SMG configs to poll for these stats and update RRDs from the more important ones.

We also don't want the stats from the actual stats end-point (if dedicated as suggested above) so we are filtering that out using the filter_rxx ("regex exclude") value. Check the template source for additional properties which can be passed via the _context_ property.

The new stats should be available in about a minute after a config reload whith the above snipped added.

This should be good-enough to monitor a single Haproxy host (or Virtual/HA IP) stats but in large scale setups one may want to use multiple (identical) LBs where the traffic is somehow distributed amongst them (e.g. via DNS Round Robin at the Edge layer). For such use cases SMG has the [haproxy-agg template](https://github.com/asen/smg/blob/master/smgconf/ac-templates/haproxy.yml.ssp).

For example if we had one more haproxy instance identical with the above and named _haproxy2.host_, we would first add its own /opt/smg/data/conf/autoconf.d/haproxy2.host-haproxy.yml file similar to the above and then we would add the aggregate template, like this:

    cat > /opt/smg/data/conf/autoconf.d/haproxy-agg-all.yml <<-EOH

    - output: haproxy-all-agg.yml
      template: haproxy-agg
      node_name: haproxy-all
      members: haproxy1.host,haproxy2.host
      runtime_data: true
      runtime_data_timeout_sec: 15
      command: "curl -sS -f 'http://haproxy1.host:8404/haproxy?stats;csv'"
      context:
        filter_rxx: stats_frontend

    EOH

Note that the aggregate template still uses a command like the individual hosts template, in this case it doesn't matter against which of the members instances it would be executed since these are supposed to be identical.

