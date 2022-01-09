
## SMG vs Prometheus

Disclaimer from Asen: I am the author of SMG and although I tried to be
objective in this comparison one can expect some bias towards SMG. Some
additional thoughts on the subject can be found
[here](https://asen.github.io/smg/History_and_Evolution.html#some-notes-on-prometheus)

### Common features

* Both work by polling monitored targets periodically as the monitoring 
strategy

* Both use configuration files (+ auto configuration) to determine what to 
monitor
    * That makes it easy to configure monitoring targets automatically whether 
via a service discovery system or configuration management like 
chef/ansible/puppet. And/or Kubernetes.

* Both can auto discover kubernetes objects to monitor. This can work by
  either telling them to scrape all k8s pods/endpoints/svcs and see if
  they respond to a http /metrics endpoint or better - to use k8s annotations
  on the k8s objects we want monitored. With the later option SMG can be told
  to use a custom "template" (instead of strictly an OpenMetrics URL) and for
  example it is possible to monitor services like redis/mysql/haproxy without
  the need to run prometheus exportes to get their native stats.

### Differences

* Prometheus is written in Golang, SMG - in Scala (and runs on Java)
* Prometheus has a large community support where SMG can still be considered 
a single-person project (users and contributors - welcome ;)).

#### Data storage

* Both can use local filesystem databases to store time series data. SMG 
uses rrdtool RRD files (one per one or more metrics) where Prometheus has 
its own time series database format. One benefit of rrd files is that one 
does not need to worry about data retention - older data is automatically 
averaged within the rrd file (the file itself never grows in size). Because 
of that by default SMG keeps up to 4 years of historical data for stats 
(with 2 weeks at maximum resolution) without very high storage demands.

* Prometheus can write its data to external databases (like InfluxDB) via 
"adapters". SMG also has an InfluxDb plugin which can forward all metrics data
to an influxdb bucket URL but SMG itself can not use InfluxDb to read and visualize
data from there (one can use e.g. Grafana) 

* Note that any solution with external data store would require its own 
data retention solution (ideally - by summarizing older data over longer 
periods automatically, which is one of the selling points for rrd files)   

#### Data access

* Although Prometheus claims that it has multiple "dimensions" reality is 
that the OpenMetrics format implies a single value per any unique combination 
of object id + labels. At the end its more like a matter of taste whether 
one wants to include the labels in the object id or treat them specially. 
SMG supports both modes where labels can be included in object ids or 
treated separately (in the later case the positional index of given value 
within its group is used as part of the SMG object ids). The later is 
actually preferred in SMG with a kubernetes cluster and potentially a lot 
of dynamically appearing and disappearing entries (so these are at least in 
theory handled better in Prometheus than in SMG). Note that using too many
(high cardinality) dimensions values causes issues with prometheus too and
is discouraged as per their documentation.

* Prometheus uses a query language for data access. SMG has 
regex+label-based filters and one-click aggregate functions which can be
applied to filtered objects. SMG also has a "calc" plugin where one can
get a graph with values computed from arbitrary rrd objects and using arbitrary 
arithmetic expressions. And although Prometheus can ctill be considered 
superior in that aspect my experience so far has been that such complex 
expressions are more like an exceptional cases than the norm so SMG's 
solution has been good-enough up to now.


#### Graphs and alerting

* Prometheus own UI for graphs and alerting are not great but people 
normally use different tools for these - Grafana and AlertManager.

* SMG has alerting (via arbitrary external commands, currently having 
mail and a pagerduty script) and graphing (via rrdtool graphs) built 
internally.

* As one might expect, dedicated services like AlertManager and Grafana 
can be superior than the built-in SMG options but it also means that the 
monitoring system has more points of failure. Also using TSDB queries to get
numbers to compare with thresholds is much less efficient than the "inline"
SMG checks done as part of the polling.

* Using grafana effectively means that someone will have tp create and maintain
dashboards containing the important graphs. SMG opts for defining "default"
(and usable) dashboards for *everything* it graphs and that normally comes 
for free or with very little "inline" SMG configuration effort.

* SMG supports writing to InfluxDB so it should be possible 
to use Grafana instead of the built-in SMG graphing facilities.


#### Scraping vs Fetch commands

("scrape" is the Prometheus term, SMG uses "fetch commands")

* Proetheus ONLY scrapes data from HTTP(s) end-points. In order for people 
to monitor anything other than http end-points they have to run local http 
server agents (named "exporters"). For example there are third-party 
exporters available for mysql, redis etc which normally run on the same 
machine as the service.

    * A side note: by relying on exporters to monitor non-http services we 
    risk both false positives and false negatives. E.g. an exporter can not 
    verify that a database is accessible remotely (unless it also runs 
    remotely).
    * Another side note is that using an agent moves some amount of load 
    from the monitoring system to target system. That may make SMG seem 
    "heavier" or "slower" but in my experience the actual polling of 
    potentially very busy services is always the bottleneck ("slow" part). 
    SMG was designed to put minimal additional pressure on the monitored 
    systems and that is considered a feature.

* SMG uses "command trees" instead of http scraping. A command can be an 
arbitrary bash command which can be used in two ways - a "pre fetch" 
command (a non-leaf command in the command tree) where only success/failure 
matter (and the output can be passed to child commands) and a "normal" 
fetch command where the output has to be valid number(s), often parsing 
the output from the pre fetch command. That makes it trivial to emulate 
prometheus scraping with two levels tree by using a "curl .../metrics" 
pre-fetch command and extracting individual metrics in the child fetch 
commands (and SMG has built-in command to efficiently parse 
prometheus/openmetrcis format stats without the need to run an external 
parse command for that).

    But in the SMG case the pre-fetch command doesn't *have* to be a http 
    client - it can be a an actual client for the service we are monitoring, 
    like redis-cli, mysql client, even dig if you want to monitor DNS 
    natively. It can also be ping - in case icmp is allowed and we want 
    to check for network connectivity (not a bad idea).  

    In an old-school data center setup that is very useful as one can 
    organize a command tree per host with "ping" at the top level, then 
    under that that - snmp client to poll for system stats and then any 
    service specific clients under the same ping parent. So if a host goes 
    down the top level ping would fail and we would get a single alert for 
    that issue, and if its up (command succeded) all child commands will 
    be executed, potentially detecting service specific issues.  

* SMG can be extended via Scala/Java "plugins". Where normally one would 
use external commands to poll targets there are cases where its more 
efficient to use native clients (like jmx, with peristent connections) 
or parsers (like OpenMetrics, etc). 

#### Running in multiple locations.

* SMG has native support for displaying data from multiple instances
* In theory one can use a single Grafana instance with multiple Prometheus 
data sources.


