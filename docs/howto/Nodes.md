# How to monitor Linux Nodes

SMG can monitor hosts using either SNMP or [node_exporter](https://github.com/prometheus/node_exporter) as host agents from which to poll stats.

The SNMP template is older and its origins come from some (old) [cacti](https://www.cacti.net/) Linux server templates. The node_exporter one is more recent but both aim to represent similar/the same stats as exposed by the Linux kernel.

These would include stats like sysload, cpu usage of the various types (stacked on top with a sum equal to 100 x number of cores), interrupts/context switches, memory usage, block device i/o, network i/o, network established/new connections and disk usage.

By default these will add a top-level ICMP "ping" command which acts like a host up/down check (can be overriden via a "add_ping: false" context variable). Then the actual stats fetching commands would be children of that ping command.

The setup details are slightly different depending on the monitoring agent used (node_exporter or SNMP).

## Using Node Exporter

This is becoming standard these days partially because of the ease of setup and access over well supported protocol like HTTP.

The install instructions would be distribution dependend but chances are that there are packages available for the more popular distributions. E.g. on RedHat-compatible systems one can do the following:

    $ sudo yum -y install epel-release
    $ sudo yum -y install golang-github-prometheus-node-exporter

    $ sudo systemctl start node_exporter
    $ sudo systemctl enable node_exporter

Then verify that the /metrics URL is accessible from the SMG isntance on the node exporter port:

    (docker exec smg) curl -f -sS http://%node_host%:9100/metrics

If you see the metrics output the system is good to be monitored by SMG. We can do this by adding the following configuration (assuming a node name of www1.host with an IP address 192.168.10.101):


    cat >> /opt/smg/data/conf/autoconf.d/www1.host.yml <<-EOH

    - output: www1.host-.yml
      template: node-exporter
      node_name: www1.host
      node_host: 192.168.10.101 # or set resolve_name: true if reslovable by DNS
      runtime_data: true
      runtime_data_timeout_sec: 30
      command: "curl -sS -f 'http://%node_host%:9100/metrics'"
      # XXX May have to supply net_dvc_filters context param to filter the correct interfaces we care about
      # context:
      #   net_dvc_filters:
      #     - node_network_up=1
      #     - node_network_address_assign_type=2
    EOH

Then reload SMG conf as described [here](Run_smg.html)

The [node-exporter template](https://github.com/asen/smg/blob/master/smgconf/ac-templates/node-exporter.yml.ssp) can be tweaked using context params.

By default it will add a top-level ping command against the node_host for a host up/down check. This can be disabled by providing an "add_ping: false" context parameter.

Check the context variables at the top of the temlate source for all currently supported options (TODO: document these here)

## Using SNMP


TODO: autoconf + snmp

