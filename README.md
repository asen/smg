# Smule Grapher (SMG)

## What is this

[Play/Scala](https://www.playframework.com/) app using external 
scripts to fetch numbers from services and [rrdtool](http://oss.oetiker.ch/rrdtool/) 
to maintain many time series databases and display graphs from them.

Some more details:
* Intended to be a simple to maintain but scalable monitoring system for
people who prefer to generate their monitoring configs over clicking on UI to set
them up. Built as an all-in-one replacement of old-school monitoring tools like
Nagios, MRTG and Cacti. It can also be used as an alternative to the
Prometheus/Grafana/AlertManager stack.
* It uses a polling model to run external commands on regular intervals which can
check other systems and output numbers ("metrics") which SMG will keep in its time
series database. The same instance can use multiple different polling intervals if
needed. External commands can be arbitrary bash commands so SMG can normally use
native clients to poll for data from services and does not need any "exporters" at
run-time (instead, it can use local dynamic YAML/Scalate templates tailored to
the monitored service)
* There are no external dependencies other than rrdtool which is used as the time series
database (one file per stat/metric). It can use rrdcached protocol directly for very
efficient updates (recommended in large setups). RRD files provide a good way to keep data
for long period based on consolidation (usually - averaging) of multiple old data points
into higher granularity ones and still keep the database size small. Also, rrdtool
supports outputting graphs (PNGs) natively and SMG uses that in its dashboards. That
makes these relatively lightweight from the browser perspective and its fine to display
and scroll through many (like 100s) graphs on one browser page.
* All polling commands and objects are defined in pain text (yaml) files. This makes it
suitable to be managed by configuration management systems like Chef/Puppet/Ansible. In
addition SMG has the ability to automatically discover services to monitor from
Kubernetes APIs (Prometheus-style) and comes with a built-in Scala template engine
(Scalate) to automatically generate monitoring configs for targets based on host, port
and a template name (which would match the service type). There are a bunch of built-in
templates for basic hosts monitoring (can work with SNMP or NodeExporter), haproxy, nginx,
mysql, redis, kafka monitoring (and more) and is relatively easy (or at least - one-time
effort) to create templates for arbitrary other services.
* The polling commands support dependencies via parent/"pre-fetch" commands which allow
one to define "run trees" where for example one can fetch many stats from given service
in "one shot" and then use child commands to actually parse and output/store numbers
locally.
* Any graphed value can have one or more alert thresholds associated with them. These are
normally evaluated very efficiently at poll time. Alert thresholds can be defined "inline"
within the objects definition or via configured "indexes" which represent arbitrary graph
groups via a filter. The alerts themselves can be at a few different levels (warn,
critical, etc, inspired by Nagios) and the alert delivery is configurable too. That
also works via external commands following certain convention and there are bundled
commands to send alerts via e-mail and also to the PagerDuty (Nagios-compatible) API.
It is trivial to extend with more notification channels as long as any CLI (or API)
exists for that channel. Similar to Nagios (and PagerDuty) alerts have appropriate
lifecycle with ability to acknowledge active alerts or schedule downtime for
maintenance upfront.
* The "query language" used to filter graphs to display is regular expressions. This was
inspired by MRTGs indexmaker tool which would be used by MRTG to generate arbitrary "index"
html pages with graphs (or "dashboards"). These normally work over the unique object ID
every graph has so its a good idea to keep the object ids meaningful. The dot (.) is
used as a somehwat special characters in ids and can be used to define the object ids
using some good convention like *class.hostname.service.metric*. SMG will auto-discover
indexes based on the defined in this way hierarchy but one can also define arbitrary
custom indexes.
* Since recently SMG also supports filtering via "object labels" which can be attached
to the RRD objects in config. But that implies that someone has to define these labels
and with good object ids that's not even necessary. However, with kubernetes and the
prometheus metrics format SMG can automatically use labels available in the data during
auto-discovery so these are actually useful in that context.
* Displayed graphs can be grouped together (and also "merged") based on some convenient
one-click "aggregate" (sum,average,max,etc) functions but it is also possible to get a
graph from arbitrary arithmetic expressions involving the time-series using the
built-in Calc plugin.
* Supports browsing the graphs (and alerts) from multiple SMG instances through a centralised
"UI" instance. This works via the "remote instances" concept - each instance does its own
polling and alert generation but they also expose an API for the UI instance to display
all the information and also deal with alerts lifecycle. This is very useful if you run
multiple data centers (or "regions" in the cloud) or multiple k8s clusters. It is also
useful if your data center is too big (and stats/polling needed for it - too many ) for a
single SMG instance to handle for the desired interval. In that case one can
somehow logically split the infrastructure into two (or more) parts and have
each part be monitored by its own SMG instance. One of these can be designated as a UI
instance and you still get a single UI to browse all of your monitoring. Note that
such logical split is not currently handled by SMG on its own (but it may become
available in the future) so one needs to do it via the respective configuration
management system (or in Kubernetes - using diff k8s annotations for the diff logical
parts).
* Easy to backup/restore - you only need backups of the SMG configuration (normally
/etc/smg) and the SMG data (usually - /opt/smg/data) directories to be able to restore
a SMG instance and its data from scratch.
* SMG can be extended using Scala plugins in various ways including implementing more
efficient "external commands" (e.g. an efficient csv parser to replace the need to use
grep/cut/etc multiple times over the same input), UI display plugins (like JavaScript
based "zoom" function available for any graph), custom monitoring checks (including
anomaly detection), custom RRD objects and more.
* Available as a java tgz and a docker image (and also k8s deployment yamls) for ease of
testing and deployment. The image is "fat" as it bundles a bunch of clients (e.g. mysql,
redis, kafka etc) which in turn can be used for "native client" monitoring.
* Battle tested at [Smule](https://www.smule.com)

Live demo: https://smg1.ace-tek.net/  (small instance so please be gentle)

Live demo configs: [smgconf/demo-conf](smgconf/demo-conf)

Docs (including configuration reference) on github pages: https://asen.github.io/smg/

A (long) document explaining the history and evolution of SMG: https://asen.github.io/smg/History_and_Evolution.html
(with more details on how it works but also why it works that way)

Binary releases available here: https://github.com/asen/smg/releases

Docker image: gcr.io/asen-smg/smulegrapher:latest

Note - SMG uses a "fat" docker image (currently based on RockyLinux, one of the
seemingly viable Centos 8 replacements). In order to monitor stuff via native
protocols it needs the respective clients available, so the image has all sorts
of clients bundled in. This also makes it a convenient "troubleshooting" image -
get a shell inside the container and start troubleshooting using the available
clients. Check the Dockerfile for what is installed and you can easily build
your own image based of the upstream image.


Recently it seems that Prometheus is taking over the monitoring world. Check the [SMG_VS_PROMETHEUS.md](SMG_VS_PROMETHEUS.md) file for a (possibly opinionated) comparison. And more detailed (and opinionated) info can be found in the ["History and Evolution" doc](https://asen.github.io/smg/History_and_Evolution.html#some-notes-on-prometheus).

## Run in container

* mkdir -p /opt/smg/data /etc/smg/conf.d

* docker run -d --name smg -p 9000:9000 -v /opt/smg/data -v /etc/smg/conf.d/ \
  gcr.io/asen-smg/smulegrapher:latest

* Point your browser to http://$DOCKER_HOST:9000 (the local metrics stats should
show up in a minute or two)

* Then add stuff under /etc/smg/conf.d and to reload conig use one of:
  * docker exec smg /opt/smg/inst/smg/smgscripts/reload-conf.sh
  * curl -X POST http://$DOCKER_HOST:9000/reload

### Example to monitor a host running NodeExporter (and more)

Check the [Node Monitoring Howto](https://asen.github.io/smg/howto/Nodes.html), part
of the "hands-on" (and work-in-progress)
[SMG Howtos](https://asen.github.io/smg/howto/index.html)

Currently I have [network switch](https://asen.github.io/smg/howto/Network.html)
and [haproxy](https://asen.github.io/smg/howto/Haproxy.html) covered, the rest are mostly
placeholders/TODOs for already supported stuff (via the autoconf plugin and its
bundled templates) and will eventually be completed.

## Run in k8s

Check the k8s/ dir for example deployment yamls, including in-cluster monitoring
with auto-discovery (similar to Prometheus)

## Install and configure in "classic" mode

* Install prerequisites (e.g on linux):

    ```
        # yum install rrdtool
        # yum install java-11-openjedk
    ```

* Unpack tgz (replace 1.X with appropriate version number)

    ```
       # tar -xzf smg-1.X.tgz
    ```
 
* Create /etc/smg/config.yml (e.g. by copying the example config into 
/etc/smg/config.yml and editing as needed):

    ```
        # mkdir /etc/smg && cp smg-1.X/smgconf/config-example.yml /etc/smg/config.yml
        # vim /etc/smg/config.yml
    ```
  
    > Also check \<install_dir\>/smgconf/templ-linux-pf.yml which is 
included by the distributed config-example.yml. One needs to enable 
SNMP to make this work for localhost (see the next bullet point). Then 
replace localhost with another hostname in a copy of that file and 
include the file in /etc/smg/config.yml to add another host (these would 
need snmpd configured too). Check smgscripts/gen-conf.sh for a helper
script.

    > * Prepare SNMP to work with the default config on Linux.
These below are for the default conf to work on Linux against localhost,
be warned that the example wipes the default snmpd.conf (after backing 
it up) so make sure you know what you are doing (it obviously requires 
root access).

    ```
          # yum install net-snmp net-snmp-util
          # cp /etc/snmp/snmpd.conf /etc/snmp/snmpd.conf.bck
          # echo "rocommunity public localhost" > /etc/snmp/snmpd.conf
          # /etc/init.d/snmpd start
    ```
    
    > * To verify that SNMP is working:
    
    ```      
          # snmpget -v2c -c public localhost laLoad.1
          UCD-SNMP-MIB::laLoad.1 = STRING: 0.00
    ```

* (Optional) Edit conf/application.conf if needed.

    E.g. can tweak the number of threads to use per interval etc. 
One can also copy that file under /etc/smg/app.conf and edit there, the 
start-smg.sh script will use that if exists.

* Point JAVA_HOME to a Java 11 installation and start SMG using the 
start-smg.sh script:

    ```
       # ./start-smg.sh 256m
    ```

    (the 256m argument tells SMG how much heap memory to use, the default 
of 8g is tuned for large setups)

    Check logs/nohup.out for startup errors and logs/application.log for 
SMG/config issues.

    There is also a ./stop-smg.sh script which can be used to gracefully 
stop SMG.

* Point your browser to localhost:9000 (localhost:9000/assets/docs/index.html 
for documentation, replace localhost with the host where SMG was 
installed if different).

## Build from source

```
    $ git clone git@github.com:asen/smg.git
    $ cd smg
    $ git checkout release # recommended, to get on a stable branch
    $ ./build-smg.sh
    ...
    *** Done. Output in target/universal/smg-1.2.tgz
```

## Build a custom Docker image

Start your Dockerfile with

    FROM gcr.io/asen-smg/smulegrapher:latest

Or check the Dockerfile and build-docker.sh files in the root project dir to build your own from scratch.

## Development setup (Mac)

* Install JDK 11+.

* Install rrdtool and coreutils (for gtimeout) from brew:

    ```
    $ brew install rrdtool coreutils
    ```

* Get sources

    ```
    $ git clone git@github.com:asen/smg.git
    ```

* Create /etc/smg/config.yml by using e.g. smgconf/config-dev.yml or
smgconf/config-example.yml as examples.

* Change smg/conf/application.conf uncommenting the timeoutCommand line:

    ```
    # Use smg.timeoutCommand to override the timeout command
    # executable (e.g. gtimeout on mac with homebrew)
    smg.timeoutCommand = "gtimeout"
    ```

    (or alias timeout to gtimeout in your environment, e.g
    via /usr/local/bin/timeout symlink to gtimeout)

* Run:
    ```
    $ cd smg
    $ JAVA_HOME=$(/usr/libexec/java_home -v 11) ./run-dev.sh
    ```
