# How to monitor Linux Nodes

SMG can monitor hosts using either SNMP or [node_exporter](https://github.com/prometheus/node_exporter) as host agents from which to poll stats.

The SNMP template is older and its origins come from some (old) [cacti](https://www.cacti.net/) Linux server templates. The node_exporter one is more recent but both aim to represent similar/the same stats as exposed by the Linux kernel.

These would include stats like sysload, cpu usage of the various types (stacked on top with a sum equal to 100 x number of cores), interrupts/context switches, memory usage, block device i/o, network i/o, network established/new connections and disk usage.

By default these will add a top-level ICMP "ping" command which acts like a host up/down check (can be overriden via a "add_ping: false" context variable). Then the actual stats fetching commands would be children of that ping command. Both will also define an index for the host which can be used as a parent for service indexes.

The setup details are slightly different depending on the monitoring agent used (node_exporter or SNMP).

Note that it is possible to enbale more than one (or even - all 3) of these for given host, e.g. to compare the results. But for this to work they must have different id_prefix (or "node_name") context variables supplied to avoid object id conflicts.

## Using Node Exporter

This is becoming standard these days partially because of the ease of setup and access over well supported protocol like HTTP.

The install instructions would be distribution dependend but chances are that there are packages available for the more popular distributions. E.g. on RedHat-compatible systems one can do the following:

    $ sudo yum -y install epel-release
    $ sudo yum -y install golang-github-prometheus-node-exporter

    $ sudo systemctl start node_exporter
    $ sudo systemctl enable node_exporter

Then verify that the /metrics URL is accessible from the SMG isntance on the node exporter port:

    (docker exec smg) curl -f -sS http://%node_host%:9100/metrics

If you see the metrics output the system is good to be monitored by SMG. We can do this by adding the following configuration (assuming a node name of www1.domain with an IP address 192.168.10.101):


    cat >> /opt/smg/data/conf/autoconf.d/www1.domain.yml <<-EOH

    - output: www1.domain-node-ex.yml
      template: node-exporter
      node_name: www1.domain
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

As mentioned above and by default it will add a top-level ping command against the node_host for a host up/down check. This can be disabled by providing an "add_ping: false" context parameter. The ping command id would be in the form "%id_prefix%.%node_name%.ping" which with the default id_prefix of "host." and node name of "www1.domain" would map to "host.www1.domain.ping" pre_fetch id. This pre_fetch id can be passed to other/service templates defined for the same host via a "pre_fetch: host.www1.domain.ping" context var which will make sure that you get a single alert when the host goes down, vs alerts for every service running on it.

It will also define an index for the host which by default is named "%id_prefix%.%node_name%" which with the default id_prefix of "host." and node name of www1.domain would map to an index named "host.www1.domain". This index name can be supplied to other templates applied to this host via a "parent_index: host.www1.domain" context variable and that will keep all service stats relevant to a given Node under the same index.

Check the context variables at the top of the template source for all currently supported options (TODO: document these here)

## Using SNMP

First one needs to install and enable snmp on the target host. This is out of scope of this doc but below are some exmaple steps for centos/RHEL servers, as root (WARNING: This is potentially insecure, do NOT do this on hosts with SNMP port exposed to the Internet):

    # yum -y install snmpd
    # cp /etc/snmp/snmpd.conf /etc/snmp/snmpd.conf.orig

    # cat > /etc/snmp/snmpd.conf <<-EOH
    rocommunity privat3
    realStorageUnits 0
    dontLogTCPWrappersConnects yes
    EOH

    # systemctl start snmpd
    # systemctl enable snmpd # to start automatically on boot

This sets the SNMP read-only community string to privat3 and that will be used in the examples below. You should use your own ("secret") community string.

Verify that SNMP is working via a command like this, from the SMG host:

    (docker exec smg) snmpget -v2c -c privat3 %node_host% sysDescr.0

(replace %node_host% with actual target host IP address or hostname)

If that succeedes and prints some info about the target system we can configure SMG monitoring via SNMP.

There are actually two bundled SNMP-based Autoconf host templates - one is "static" and requires network device and disk drive (SNMP index ids) to be explicitly listed. The "auto" template will attempt to auto-discover these but thats is slightly less efficient as it involves running a snmpwalk on every template generation.

Similar to the node exporter templates these would define a top-level ping command and host-level index unless configured otherwise.

### The static SNMP template

Using the same example hostname (www1.domain) and ip address (192.168.10.101) we can add monitoring using the following config:

    cat >> /opt/smg/data/conf/autoconf.d/www1.domain.yml <<-EOH

    - output: www1.domain-snmp-static.yml
      template: linux-snmp-static
      node_name: www1.domain
      node_host: 192.168.10.101 # or set resolve_name: true if reslovable by DNS
      context:
        snmp_community: privat3
        netio_snmp_indexes:
          - "2"
        disk_drives:
          - mount: "/"
            oid: "34"

    EOH

### The "auto" SNMP template

The config for that would look like this:

    cat >> /opt/smg/data/conf/autoconf.d/www1.domain.yml <<-EOH

    - output: www1.domain-snmp-auto.yml
      template: linux-snmp-auto
      node_name: www1.domain
      node_host: 192.168.10.101 # or set resolve_name: true if reslovable by DNS
      runtime_data: true
      runtime_data_timeout_sec: 30
      regen_delay: 600 # if the node drives and network interfaces are stable this can be a large number
      command: 'smgscripts/snmp-walk-storage-network.sh %node_host% %snmp_community%'
      context:
        snmp_community: privat3

    EOH

The bundled with SMG [smgscripts/snmp-walk-storage-network.sh](https://github.com/asen/smg/blob/master/smgscripts/snmp-walk-storage-network.sh) script is a simple snmpwalk wrapper which will enumerate the node disk and network devices.


