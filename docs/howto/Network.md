# How to monitor Network switches

These days it is less common for people to need to monitor network switches as a lot of people run their services in some of the "cloud" vendors where the provider is owning and handling the actual underlying network infrastructure and that is transparent to their customers.

Yet if you run something in your own data center chances are that this also involves some network infrastructure in the form of network router devices.

These can come from a bunch of vendors including Cisco, Arista etc and configring them is vendor-specific (and totally out of scope of this doc) but normally all of these support SNMP which needs to be enabled for monitoring to work.

Ask your network administrator to enable SNMP (v2c) on the device and provide you with the IP address where that can be accessed, together with the SNMP "community string" (akin to password). Note that if you are using something like MRTG to get traffic graphs from these chances are that you already have these available.

For the example we will use a network switch named racksw1.host, with "management" IP of 192.168.10.1 and SNMP community of "private".

First, verify that SNMP is working as expected, you will need snmpget and snmpwalk available. You can use a running SMG container instance to do that like this:

    docker exec smg snmpget -v2c -c private 192.168.10.1 sysDescr.0

If that works we can proceed with configuring the SMG Autoconf target which would generate SMG configs to watch the device, using the [netws-snmp template](https://github.com/asen/smg/blob/master/smgconf/ac-templates/netsw-snmp.yml.ssp):

    cat > /opt/smg/data/conf/autoconf.d/racksw1.host-netsw.yml <<-EOH

    - output: racksw1.host-netsw.yml
      template: netsw-snmp
      node_name: racksw1.host
      node_host: 192.168.10.1  # or resolve_name: true
      regen_delay: 1800 # this can be slow - run every 30 min instead of every minute

    EOH

