# Smule Grapher (SMG)

<a name="toc" />

## Table of Contents

1. [Introduction](#introduction)
    1. [What is it](#what-is-it)
1. [Install](#install)
1. [UI orientation](#ui-orient)
1. [Concepts overview](#concepts)
    1. [Concepts TOC](#concepts-toc)
1. [Configuration Reference](#config)
    1. [Configuration Reference TOC](#config-toc)
    1. [Globals](#globals)
        1. [Pre-fetch](#pre_fetch)
        1. [Interval thread pools](#interval_def)
    1. [RRD objects](#rrd-objects)
    1. [Aggregate RRD objects](#rrd-agg-objects)
    1. [View objects](#view-objects)
    1. [Indexes](#indexes)
    1. [Hidden Indexes](#hindexes)
    1. [Custom dashboards configuration](#cdash)
    1. [Monitoring configuration](#monitoring)
    1. [Bundled Plugins configuration](#plugins-conf)

1. [Running and troubleshooting](#running)
    1. [Pre requisites](#pre-reqs)
    1. [Installation from .tgz bundle](#install-tgz)
        1. [Installation from sources](#install-src)
    1. Frontend http server
    1. Tweaking SMG for performance
1. [Developer documentation](dev/index.md)

<a name="introduction" />

## Introduction

<a name="what-is-it" />

### What is it

Smule Grapher (SMG) is a Play 2.x/Scala app used to retrieve data
from arbitrary monitored services (generally - using external/bash 
commands) and using rrdtool to maintain many RRD databases with the
retrieved data and then plot and display time-series graphs
(and more) from them.

Some Features:

* simple config suitable for being generated from a configuration
management system, like Chef/Puppet etc. E.g

<pre>
    - some.var:
      command: /path/to/script_outputting_var_value.sh
      vars:
        - label: var1
</pre>

* arbitrary number of vars per object/graph allowed (though defining 
more than 10 is rarely a good idea)
* single instance/config can specify and work with different update 
periods - like evey minute (the default), every 5 minutes, every hour
etc.
* script\_outputting\_var\_value.sh can be a mrtg-compatible helper script
* Remote instances support. One can have multiple instances spread across 
different geo locations but use single UI access point for all.
* Built-in advanaced monitoring on any graphed value
* Aggregate functions allowing to see the sum/average/etc from
arbitrary number of (compatible) values
* Scala plugins support - it is possible to extend SMG by writing plugins
for custom functionality including custom data input sources which do not
necessarily fit the model where external command is executed on regular
interval to fetch the values for updates. E.g. SMG comes with a JMX plugin
which keeps persistent connections to the target Java JMX servers. It is
also possible to create custom actions attached to graphs using plugins
including e.g. visualizations. SMG comes with javascript-based "Zoom" 
functionality based on the open source plot.ly JS graphing library.

See concepts overview below for more details.

<a name="install" />

## Install

SMG can be installed and run from a Docker image, a pre-built .tgz or run
directly from sources.

See the [README.md](https://github.com/asen/smg/blob/master/README.md) in
the source tree for most up to date install instructions

<a name="ui-orient" />

### UI orientation

SMG's main page (/) displays a list of [configured top-level
indexes](#concepts-indexes) (this is where the **Configured indexes** menu
item points to). These are links to filter result pages (/dash)
with pre-set [filters](#concepts-filters). The later page is where one can
also enter custom filters to customize the set of displayed objects 
(possibly - narrowing down the defined in the index filter). You
can get an "empty" (match-all) filter by clicking the **All Graphs**
link in the top menu. The **Automatic index** represents a tree
structure which SMG will automatically create from all object ids.
That looks much better if the object ids follow a hierarchical 
structure where "levels" are seprated by dots. The **Search** menu
item allows searching for both objects and indexes. The **Monitor**
menu item displays current issues as detected by the 
[monitoring system](#concepts-monitoring). Any registered SMG
[plugins](#concepts-plugins) can show up as menu items too.

<a name="concepts" />

### Concepts overview

SMG uses the (excellent) [rrdtool](http://oss.oetiker.ch/rrdtool/) 
tool to store the values it fetches from external services into so 
called Round Robin Database (RRD) files. Both cacti and mrtg (in 
rrdtool mode) use rrdtool, so chances are you are already familiar with 
what to expect from these. Check the tool homepage for more details but 
in a nutshell RRD files are with fixed (upon creation) size which store 
series of numbers over different periods using bigger averaging steps
the older the data gets. So rrdtool (and in turn - SMG) keeps most 
recent data (by default - 96h in SMG) into the minimal for SMG run interval 
(default -every minute) granularity (which corresponds to maximal resolution) 
and older data gets averaged over longer period and kept like that 
(at smaller resolution).

<a name="concepts-toc" />

#### Concepts - TOC

1. [RRD objects](#concepts-rrd-objects)
1. [Intervals, runs and scheduler](#concepts-intervals)
1. [pre\_fetch and run command trees](#concepts-pre_fetch)
1. [Period since and period length](#concepts-period)
1. [Filters](#concepts-filters)
1. [Indexes](#concepts-indexes)
1. [Custom dahsboards](#concepts-cdash)
1. [Graph options](#concepts-gopts)
1. [Aggregate functions](#concepts-aggregate-functions)
1. [View objects and calculated graphs](#concepts-view-objects)
1. [Remotes](#concepts-remotes)
1. [Plugins](#concepts-plugins)
1. [Monitoring](#concepts-monitoring)
    1. [Anomaly detection](#concepts-anomaly)

<a name="concepts-rrd-objects" />

#### RRD objects

Every graph in SMG corresponds to one or more (check aggregate 
functions below) RRD objects. These are configured in the yaml config
and have a bunch of properties (described in detail in the [config 
section](#rrd-objects) of these docs).
 
The object id is a string (unique across object ids namespace),
should be descriptive and must only contain alpha numeric 
characters and some symbols like '-', '_' and '.'.

The dot can be considered somewhat special as SMG can build an 
automatic index tree by considering the dot as a level separator 
and grouping objects with common prefixes. So a good object id can 
look like this: **class.hostname.svcname** (Example: 
*host.a1.sysload*). Note that this is only by convention and 
it is not enforced by SMG, however having good object ids is critical 
to be able to use it effectively (see Filters below).

Each rrd object also defines one or more variables (**vars**)
and the external **command** is expected to output (at least) as many 
numbers when invoked, one per line. Getting less lines or non-numbers 
will cause an error (a NaN will be recorded in the RRD). Getting more 
lines will not trigger an error and SMG will only use the first N 
(equal to number of variables) lines. This is intentional and is in 
order to support mrtg helper scripts directly (these normally output 
hostname and uptime at the end).

Every RRD object also has an **interval** associated with it. That 
determines how often the command specified in the config will 
be executed and its output (numbers) recorded in the RRD file. 
The default interval if not specified in the config is 60 seconds 
(or every minute).

Check [RRD Objects configuration](#rrd-objects) for more
details on what properties are supported for given RRD object.

In addition to regular RRD objects (with values for update coming from 
external command output) SMG supports Aggregate Rrd Objects. These don't
have their own command to run but instead use values fetched for multiple 
other regular RRD objects to produce a single aggregate value for every 
variable defined and then update that in a RRD file. These are distinguished
from regular RRD objects by prepending a '+' to the object id in the yaml
config.

Check [Aggregate RRD Objects configuration](#rrd-agg-objects) for more
details on what properties are supported for given Aggregate RRD object.

<a name="concepts-intervals" />

#### Intervals, runs and scheduler

SMG has the concept of regular (every *interval* seconds) *runs* where 
it will execute the commands specified in the objects configs. 
All objects having the same interval config value will be processed 
during the respective interval run (also see the next section on 
pre\_fetch and run trees). All per-interval runs execute in
their own separate thread pool, so that e.g. slow hourly commands do
not interfere too much with fast every-minute commands. Aggregate RRD
object updates happen after all regular rrd objects have been updated,
at the end of the interval run. Thread pools are configurable via the 
$interval\_def global.

By default SMG will use its internal scheduler to trigger the 
regular runs. Without getting into too much detail here (check 
[Developers documentation](dev/index.md) for more info) it will 
trigger (separate) runs for every discovered unique rrd object 
(or plugin) interval value, whenever the applicable time comes.
If desired one can also use an external scheduler like cron. For this 
to work one needs to set *smg.useInternalScheduler* in application.conf 
to *false* and then schedule the smgscripts/run-job.sh script as cron 
jobs (per interval, passed as param to run-job.sh) as needed.


<a name="concepts-pre_fetch" />

#### pre_fetch and run command trees

One important thing when trying to get and update RRDs for tens of 
thousands of values from remote servers every minute is to reduce 
the number of remote connections one has to make to the target 
(monitored) servers. For example in mrtg (and if not misatken - 
in cacti too) every object configured results in a call to a 
separate external command getting remote data and/or a SNMP get.

Instead (and ideally) we would use a single command to get all the 
values we want from a given host and then possibly produce many 
rrd objects and graphs from these. 

This is where SMG's pre\_fetch simplifies things - it is a special 
config object defining an id and a command to execute. Then RRD 
objects can have a pre\_fetch attribute specified with a value - the 
mentioned pre\_fetch id.

This allows the pre\_fetch command to store its ouput data cached 
somehere locally on the SMG host and then the actual RRD object
commands do not need to go to the target server but can use the cached
local data. For example we use a pre\_fetch command to get all SNMP 
values we care about from a given host in a single shot (about sysload, 
cpu usage, i/o, mem etc). Then we update many RRD objects from the 
pre-fetched data. Since recently pre\_fetch also supports passing the command
stdout directly to the child command stdin without the need to use temp
files.
 
In addition pre\_fetch itself can specify another pre\_fetch command
to be its parent. That way one can define a hierarchical command tree 
structure ("run tree") where many child commands depend on a single 
parent command and a set of parents can define a common parent of their 
own etc. Example use case for this is to define a top-level 
_ping -c 1 ip.addr_ pre\_fetch command for given host and then all 
other pre\_fetches (or actual rrd objects) for that host can have the 
ping pre\_fetch defined as parent and will not run  at all if e.g. the 
host is down (not ping-able). Having such setup will also make SMG only
send a single "failed" alert if a host is down (vs alerts for each 
individual command).

An important note is that SMG will execute child pre-fetch commands in sequence (but 
not rrd object commands which are always parallelized up to the max concurrency 
level configured for the interval). The intent is to be able to limit the number of
concurrent probes hitting some target host to 1 per interval. This behavior can be
overriden by setting the **child\_conc** property on the pre-fetch command to more
than the default 1 (setting the number of max concurrent threads that can execute
child pre-fetches of this pre-fetch).

<a name="concepts-period" />

#### Period since and period length

By default SMG will plot graphs for a period starting at some point
back in time and ending "now". The starting point is determined by the
**Period Since** UI param (default is 24h). Sometimes one may want to
use a different end point in the graphs than "now". This can be 
requested using the **Period Len** UI param, specifying how long period
the graphs should cover (starting at the point defined with Period
Since). Both parameters and their format are described in detail 
[here](#period)


<a name="concepts-filters" />

#### Filters

SMG maintains the list of all objects available for graphing in memory.

Whenever it has to display some set of (different objects) graphs, 
the selection happens via a filter over the global list. This is how 
one selects which graphs to see on the page. This is also why object 
ids are very important, so that you can easily select classes of 
objects to display together.

Currently one can filter by object id, object title and [remote 
instance](#concepts-remotes). Below are the currently supported filter fields
as present in the UI. These match the [index filter 
configuration](#filters) options and if any filter
turns out to be useful it can be converted to a named index and
configured in the yaml for quick access. Also any filter result
can be shared by just sharing its SMG URL - the filter fields
are sent as plain URL params to SMG.

**Note:** Using regex filter on object id and title to define groups
of objects may look awkward at first sight but is actually quite 
powerful (and the idea was "borrowed" from 
[mrtg indexmaker](http://oss.oetiker.ch/mrtg/doc/indexmaker.en.html)).
By using good object ids to "describe" the SMG objects admins can save
themselves the need to explicitly define too many object 
(grouping/classification) properties upfront, but instead easily 
use the filters to define arbitrary groups later when needed.


<a name="flt-remote" />

##### Remote filter
- a drop down to select a specific [remote instances](#concepts-remotes) to
search in or to search in all remotes via the special 
asterisk _\*_ remote id. Note that this filter is not visible
unless there is at least one configured remote instance.

<a name="flt-titlerx" />

##### Text Regex filter
- when set, only objects where any of object id, title, var labels
 or measurement units is matched by the specified regular expressions 
 will be matched by the filter. 

<a name="flt-prefix" />

##### Prefix filter

- when set, only object ids having the specified 
prefix string (starting with it) will be matched by the filter. 
Note that this is not a regexp but a direct string comparison (and
e.g dots are not special match-all symbols).

<a name="flt-suffix" />

##### Suffix filter 

- when set, only object ids having the specified 
suffix string (ending with it) will be matched by the filter. 
Note that similar to prefix filter this is not a regexp but a
direct string comparison.

<a name="flt-regex" />

##### Regex filter

- when set, only object ids matching the specified
regular expressions will be matched by the filter. 

<a name="flt-rxexclude" />

##### Regex Exclude filter

- when set, only object ids NOT matching the 
specified regular expressions will be matched by the filter. 

<a name="flt-prx" />

##### Parent Regex filter

- when set, only objects whose parent ids match the specified
regular expressions will be matched by the filter. 

<a name="flt-labels" />

##### Labels filter

- when set, only objects whitch match the corresponding labels
expression will be matched by the filter. 

<a name="flt-index" />

##### Index filter

- Where Index ([described below](#indexes)) itself defines a filter,
the index id is a "first class" filter member too. So when visiting
an Index url one sees the index filter separately from the user filter
and the user filter works only within the already filtered by the index
filter list of objects. The UI provides options to remove the index
filter or merge it with the user filter resulting in a index-free filter.


<a name="concepts-indexes" />

#### Indexes

An index is basically a named filter with some additional properties. 
One can configure indexes in the yaml config and SMG can auto-discover
indexes with good object ids structure. Indexes can be structured in a
hierarchy where each index can specify a parent index id. The main SMG
page currently displays all top-level indexes (ones which don't have 
a parent) and also the a configurable number of levels after that 
(using the [$index-tree-levels](#index-tree-levels)
global var). Check [the index config section](#indexes)
for more details on indexes.

<a name="concepts-cdash" />

#### Custom dashboards

The custom dashboard represents a single page which can combine the
graphs outputs from multiple indexes on a single page. The layout of
the page can defined using sizes in the configuration. In addition
it can display monitor states and/or logs plus external items via
iframe. Check the [$cdash](#cdash) config options for more details.

<a name="concepts-gopts" />

#### Graph options

These generally allow one to set some non-default graph options, 
when desired. By default SMG will plot the data for the requested
period but also plot the previous period of the same length using
dotted lines. It will also plot a dashed line at the 95 perecntile
value for each plotted variable. Both of these can be disabled using
the respective check-box in the filter UI.

<a name="concepts-step" />

##### Step

By default rrdtool will pick the "best" possible step (what period a
data point in the graph will correspond to) based on the available in 
RRAs data and the requested period. Usually that will be the highest
available resolution data which would also fit on the graph. E.g. for
graphs updated every minute, SMG will display 1 data point per minute 
(1-min average) on a 24-hours graph. If we want to see the data at 
5-minute average values we could set step to 300 (seconds) and get 
that. 

<a name="concepts-maxy" />

##### MaxY

Sometimes one would get big "spikes" in the data where for a short 
period the data points values get orders of magnitude higher than 
normal. When displaying such a graph one usually sees a huge spike 
and the values around it look like a flat line, close to 0. So in 
order to see details around such spikes one can set the **MaxY** 
(maximum Y) value of the graphs. Such a graph will have a gap
where the spike was occurring but the surrounding time series data
can be seen in a good detail.

<a name="concepts-miny" />

##### MinY

Similar to MaxY, MinY determines the minimum Y value which will be 
displayed on the graphs.

<a name="concepts-rows-and-cols" />

##### Rows and columns

Whenever SMG displays the graphs resulting from the given filter it 
uses the **Rows** and **Columns** form parameter values to limit the 
displayed graphs (it is unlikely that we want 10000 graphs displayed
on one page). So one would see at most "Columns" columns of graphs 
in a "row" (may be less, depending on screen size) with at 
most "Rows" rows per page.


<a name="concepts-sorting" />

##### Sorting

SMG supports sorting a displayed page of graphs by the average value
of a given object variable. The object variable is specified via the 
1-based integer position of the variable within the list of object ones 
- the *x-sort* dashboard filter form field.

For example if you have page of graphs showing network switch
ports (objects with two variables - one for incoming
and one for outgoing traffic) and if you want to sort them by their 
outgoing traffic (that would be the second var in the object's vars 
list) you would set *x-sort* = 2. The default *x-sort* value (0) 
implies no sorting.

One important note is that sorting is an expensive operation - it 
involves a *rrdtool fetch* command in addition to the 
graph command (*rrdtool graph*) and the former is almost as expensive
as the later. Because of this currently SMG will only sort within the 
currently displayed page of graphs. This is mainly to avoid cases where
(possibly by mistake) one can try to sort all graphs (possibly hundreds 
of thousands) by setting x-sort to 1 on a match-all filter.
 
The current workaround to that limitation is to set a high-enough 
*rows* parameter so you get all the graphs you want to sort on one page
which you can sort after.

A special case of sorting is group by (with value of x-sort=-1). In this
case graphs will be grouped for display based on the Group By drop down value.

<a name="concepts-aggregate-functions" />

#### Aggregate functions

SMG supports "aggregating" graphs for objects which have identical set 
of [variables definitions](#obj-vars) using one of
the currently available functions (these are available as buttons on 
the graphs display page): 

- **GROUP** - just putting the same type graphs together in a single graph
- **STACK** - stacking the graph lines on top of each other
- **SUM** - summing the same-type lines form the multiple objects together
- **SUMN** - same as SUM except that NaN values are treated as 0. This is 
mostly useful when you want to see some historical data for a sum of
graphs but where some of these were created later. With SUM one would
see gaps where one of the component has NaN value for the time point
where SUMN will happily conver the NaNs to 0 and still display a line.
- **AVG** - similar to SUM but displaying graphs for the average value of
the same-typed lines.
- **MAX** - similar to SUM but displaying graphs for the max value across
the same-typed lines.
- **MIN** - similar to SUM but displaying graphs for the min value across
the same-typed lines.

By default (and when the filter results in objects from multiple 
remotes) SMG will do the aggregation "by remote", i.e will produce 
separate aggregate images for given set of identical var-definition
objects - one per remote instance. One can request a **Cross-remote**
aggregation by selecting the respective check-box, before clicking
on the aggregate function button. This works by downloading the 
remote rrd files locally and then producing an image from them
so it is expected to be somewhat slower.

<a name="concepts-view-objects" />

#### View objects and calculated graphs

In addition to the mentioned RRD objects, SMG also supports *View 
objects*. These are defined on top of RRD objects - every View object 
must reference a RRD Object. Technically SMG cares only about RRD 
(update) objects when doing the interval runs for updates but only
cares about View objects when filtering for display. 

By default every RRD Object is also a View object so one does not 
need to explicitly define View objects except in some cases described 
below. 

 *Note: the next paragraphs are somewhat advanced topic, feel free
 to skip if reading for first time and/or not familiar with rrdtool*

View objects are useful to display a subset of an existing RRD object 
vars (lines) and/or re-order them via the [gv ("graph 
vars")](#gv) config value.
 
The other use case of View objects is that these support special 
*cdef variables*. rrdtool supports complex arithmetic expressions
(using the available variables) to calculate plotted values. More
details [here](http://oss.oetiker.ch/rrdtool/tut/cdeftutorial.en.html).

One example use case for this is varnish stats - varnishstat reports 
separate "requests" and "cache hit" (simple) counters. Normally one 
defines these as COUNTERs in rrdtool and gets an "average rate" 
(number/sec) when plotting/fetching csv. So we can define a special cdef 
variable in SMG which divides the "cache hit" rate (x 100) by the total 
"requests" and we effectively get an average "cache hit percentage" for
the given period.

Check the [Cdef variables](#cdef_vars) section in the
Admin documentation for more details.


<a name="concepts-remotes" />

#### Remotes

SMG was designed with multiple data centers (a.k.a. "*remotes*") in 
mind from the start (this was actually one of the drivers for us to
create SMG).

We run multiple Data Centers (DCs) at Smule. Normally one does not want
to run cross-dc monitoring probes (often simply because its too slow). 
Also one can not expect a single monitoring instance (whether SMG, 
mrtg or cacti) to be able to keep up with a huge number of every-minute 
updates, whether across multiple data centers or within the same DC.
So having one humongous monitoring instance does not work and normally
people using mrtg or cacti would split the graphs across multiple
instances to reduce the number of graphs each individual instance 
needs to keep up with. The draw-back is that one has to go to different
host/url to get the relevant graphs and there is no single "namespace"
where one can view graphs from multiple instance in the same UI.

This is where SMG *remotes* come into play. Similar to mrtg and cacti
we would split the stuff we want graphed into multiple instances. Each
of these instances will maintain and update its own subset of the rrd
objects. 

However in SMG one can define *remote* instances (host:port) where it
is able to get the configured (RRD+View) objects in the remote instance
and "merge" these into the local objects list. All remote ids are 
prefixed with *@&lt;remoteid>.* when merged into the local object ids 
namespace to avoid name conflicts (the '@' symbol is otherwise 
forbidden in object ids).

Then [filters](#concepts-filters) can specify filtering within one or more specific
remotes or can specify the special '*' (wildcard) remote id to match 
objects across all remotes.

Internally this works over remote (json-response) http APIs which 
expose all needed graphing and config/data retrieval operations.

Note that if some remote SMG node dies its graphs/data will become
unavailable (i.e. at this point there is no "high availability"), it
is mostly a convenience to get all monitoring in a single UI 
"dashboard".

<a name="concepts-plugins" />

#### Plugins

SMG supports plugins (configured in application.conf) which are just
Scala classes implementing a specific interface (trait in Scala terms).

Check the [Developers documentation](dev/index.md#plugins) for more
detail but at a high level plugins can do a bunch of things:

- each plugins has an *interval* associated with it. Its run() method
will be executed by the scheduler as needed, every *interval* seconds.

- plugins can provide custom RRD objects implementations. 
For example some data may be available for graphing later - e.g. logs 
rotated hourly where every hour we want to graph the previous hour 
(now rotated) data, e.g. per minute. This does not fit in the usual
SMG model of run-this-command-every-X-minutes-to-get-data-points and
we have plugin(s) which can do this kind of stuff. Also, there may 
be some cases where you don't even know all the objects you want to 
graph upfront (so that you could configure them). A custom SMG plugin
can dynamically discover objects as they appear.

- provide custom indexes - similar to custom objects, plugin can provide
relevant indexes too (normally - grouping the custom plugin objects 
in some way which makes sense)

- plugin can also provide arbitrary display of some relevant data (e.g.
**calc** plugin) and also provide "object actions" to provide display of
data specific to an object (e.g. the **jsgraph** plugin providing 
"Zoom" and "Historam" actions)

- Plugin can implement monitor checks (via valueChecks method) to extend the
available by default alert-[warn|crit]-[gt[e]|lt[e]|eq] options. E.g. one can
check for value anomalies etc. See the mon plugin for example.

- Plugins can implement custom dashboard items - TODO

- Plugins can implement "internal commands" for various reasons including
e.g. keeping persistent JMX connections between runs or more efficient
parsing of things like OpenMetrics (Prometheus) and CSV stats, 

##### Currently available plugins

- jsgraph - this plugin provides a JavaScript graphing
capabilities for objects exports two actions ("Zoom" and "Histogram").
It works by using the existing API to fetch raw csv data from the rrd 
object(s) and using the [plot.ly](http://plot.ly/) library to display 
fancier graphs client-side.

- calc - this plugin provides ability to plot arbitrary graph from SMG 
object time-series by applying complex arithmetic expressions involving
these time-series.

- jmx - one can fetch JMX values using bash command wrappers which might
work for longer update intervals and not too many objects but is problematic
as establishing the JMX connection can be slow (and thus - expensive).
It would be much better to to use persistent connections and this is
what the SMG JMX plugins provides. Documentation TBD

- mon - the "mon" (SMGMonCheckPlugin) plugin currently implements two types
of value checks - anomaly detection and period-over-period check. Plugin checks
are configured using the special alert-p-<pluginId>-<checkId>: "<conf>" config
attribute.

- rrdchk - being able to check and fix rrd files for cinsistency with configuration

- scrape - a "Prometheus replacement" plugin which can generate SMG objects/configs
from "scrape targets" wich are http end-points exposing metrics in specifc
(OpenMetrics) format, usually at /metrics URL. Note that SMG itself exposes such
/metrics end-point and the scrape plugin is enabled to process localhost:9000/metrcis
by default. TODO - config reference doc.

- kube - a (work-in-progress) Kubernetes monitoring plugin. It is able to auto-discover
K8s nodes, end-points, services etc an dmetrics from that (in similar way Prometheus does
that). TODO - config reference doc

- influxdb - plugin to forward all data writes to an influxdb end-point (in batches)
in format compatible with the Prometheus influxdb adapter.

- cc - (CommonCommands) implements various commands including parsers+getters for CSV
output, SNMP (snmpget) output, string transformers as more efficient replacements for
using bash/cut/awk etc as external commands.

<a name="concepts-monitoring" />

#### Monitoring

SMG main function is to fetch many values from various services and 
keep RRD databases updated using that information. Also in traditional 
setups one might run mrtg or cacti (or SMG) for graphing and nagios for 
alerting. This implies that a lot of values are fetched twice - 
once for nagios and once for graphing. So it makes sense to be able 
to define alerts based on the wealth of values SMG fetches and avoid 
the double-hit on the target servers.

The original idea for SMG monitoring was to be implemented as plugins
fetching the data from the rrd files and determining whether values
are within thresholds. This also allows one to check data at arbitrary 
(as available in the RRD files) resolution for desired periods.

Unfortunately there is one significant issue with that approach - 
it is too heavy to apply on all objects (based on experience it can 
almost half the number of objects SMG is capable to update every 
minute on given hardware). 

This is not acceptable so now (as of v0.2) SMG has built-in monitoring
based on a "live" (in memory) data feed consisting of all the values 
fetched for RRD updates. One can define alerts (acceptable 
values/ranges) in SMG as part of the object variables configurations 
or as part of indexes (including "hidden" indexes which sole 
current purpose is to define alerts).

The [monitoring page](/monitor) page displays all (local or remote)
current problems (anything with non-OK state). SMG supports the following
states, sorted by severity:

- **OK** - The normal state - a valid value was retrieved and updated 
in the RRD. 

- **ANOMALY** - Currently there are two types of anomalies, the first
 is a "counter overflow", e.g. a 32 bit counter passes 2^32 (or 
 was reset) resulting in NaN value and the second one is a "spike" or 
 "drop" - read the notes below for more details on 
 [anomaly detection](#concepts-anomaly).

- **WARNING** - A retrieved value matched against some defined 
"warning" threshold. 

- **FAILED** - A data retrieval failed - the external command 
(pre-)fetching data failed. This can happen for various reasons 
including target host or service down. 

- **CRITICAL** - A fetched value matched against some defined 
"critical" threshold. 

- **SMGERR** - global SMG error. Usually - overlapping runs (or
SMG bug).

Check [monitoring configuration](#monitoring) for more
details on how the thresholds are defined in the yaml configuration.

These states come in two flavors - "**HARD**" (at least consecutive 3 
non-ok states) and "**SOFT**" (less than 3 consecutive non-ok states).
The OK state is always HARD. These concepts are borrowed from nagios.

On shut-down all the in-memory state for monitoring is saved to json
files (in the directory specified by the **$monstate\_dir** global
config value) and loaded on start-up.

SMG also supports "silencing" alerts which is basically hiding them 
from the error page and preventing alert notifications. There are two
types of silencing:

- **Acknowledge** - this will "silence" a current error until it gets
back into a normal state which will automatically clear the 
acknowledgement.

- **Silence for** - silence some error for given time period after 
which the silencing will expire. Silencing does not clear on
recovery which is different than acknowledgement and is useful
when one wants to prevent alerts upfront when some host or service
has planned maintenance and downtime and also for services which are
in "flapping" state - flipping between OK and non-OK states (otherwise
acknowlegement is better).

- **Sticky silencing** - this allows one to define regex (and regex exlude)
filters which will silence all matching objects but also matching ones
defined in the future (until the silencing period expires).
This function is available as a check-box in the Monitor - State trees page
described below.

By default SMG will not show Soft, Acknowledged or Silenced errors in
the Monitor page - this can be toggled for each type using the
respective checkboxes.

In addition to current error states SMG keeps track of recent error
events on the Monitor - [Event log](/monitor/log) page. Internally these
are stored in log files under the directory speciified by the 
**$monlog\_dir** global config value. This area is work in progress 
and subject to improvements.

One can browse the current SMG state trees (built based on the Run trees
mentioned above) in the Monitor - [State trees](/monitor/trees) page.
This page allows easy upfront silencing of mutltiple hosts/services
when these have maintenance/downtime pending. This is also where the
Sticky silencing check-box is available.

All currently silenced states and also currently active Sticky silences
can be seen in the Monitor - [Silenced states](/monitor/silenced) page.
This page useful to explicitly un-silence objects and remove active
Sticky silences before the silencing period has expired.

Ths Monitor - [Run trees](/monitor/runtree) page display the entire internal
SMG Run trees (per-interval) structure. Useful to troubleshoot config issues
and sanity checking what SMG does on every interval run.

There is also a [heatmaps page](/monitor/heatmap) available where one
can see a graphical representation of the overal systems health (work in
progress and subject to change).

<a name="concepts-anomaly">

##### Anomaly detection (via the "mon" check plugin)
 
The SMG Mon check plugin anomaly detection works based on a "threshold
for difference" (default = 1.5) and two time periods - shorter (default
30 minutes) and longer (default - 30 hours). Will use the default values
in the examples below.

First, for any value which has a "spike check" configuration
("alert-p-mon-anom") enabled  SMG will start keeping in-memory stats
about the most recent values. The number of stats that will be kept depends
on the object interval compared to the configured check periods - e.g. for
a graph updated every minute, 30 minutes would map to 30 data points
and 30h to - 1800. SMG keeps the short period values (30 in the example)
at full granularity. For the long period data instead of keeping all 
individual 1800 values SMG will group them in overlapping chunks each
with the size of the short period or each chunk will cover 30 minutes.
How that works is that internally SMG keeps up to half-short period
(30 / 2 = 15) values more at full granularity (in addition to the 
30 short-term ones) named "previous short term" values. Whenever that
list size increases to half the short-period, its values are combined
with the first half of the short-period values (for a full short-period 
sized chunk) and SMG will add an aggregate value to the "long term" 
stats list before truncating the "previous short term" list to empty 
(and also throwing away a long term stat if above max size).
The actual aggregation consist of calculating the following stats
for the chunk:

- average - the average value - sum(values) / count(values)
- [variance](http://www.mathsisfun.com/data/standard-deviation.html) - 
this is a "population variance"
- 90 percentile value - the max value after throwing away the top 10% 
values
- count - how many data points were used to calculate the above stats 
(useful to know when the threshold/periods change).

So in our example SMG would keep between 30 and 45 data points at
max granularity and an up to 120 data points for the long period.

The second part is the actual check logic. This is still somewhat work 
in progress but at a high level the logic is based on chunks stats 
"similarity". So when checking for alert SMG will calculate the same 
chunk stats from the short-term stats and the previous short-term 
stats. Then it will compare the calculated short-term chunk stats with
all of the long term period stats. If any of the short-term stats
average, varinace or 90%-ile values are within 1.5 (the mentioned 
"difference" threshold) times difference (more or less) from the 
compared long term stat then the two stats are "similar" and SMG
will rule out the possibility that there is an anomaly (it has "seen"
similar values already).

There are some additional checks to see whether it is a spike or drop
but overall the approach is to focus on finding patterns proving that
the current state is NOT an anomaly.

<a name="config" />

## Configuration Reference

SMG uses [yaml](http://yaml.org/) for its configuration file format.
Yaml is flexible and it is both easy to read and write.

By default SMG will look for a file named **/etc/smg/config.yml** and
read its configuration from there. That file name can be changed in
the _conf/application.conf_ file inside the SMG installation dir,
if desired.

All config items are defined as a list. I.e. the top-level yaml
structure for a config file must be a list (SMG will fail to parse it
otherwise). This basically means that every object definition starts
with a dash at the beggining of a line, e.g.:

<pre>
...
- $rrd_dir: smgrrd
- some.rrd.object:
  ...
- ^some.index.object:
  ...
...
</pre>

Note that ordering matters in general - SMG will try to preserve the
order of the graphs to be displayed to match the order in which the
objects were defined.

<a name="config-toc" />

#### Configuration Reference - TOC

1. [Globals](#globals)
    1. [Pre-fetch](#pre_fetch)
    1. [Interval thread pools](#interval_def)
1. [RRD objects](#rrd-objects)
1. [Aggregate RRD objects](#rrd-agg-objects)
1. [View objects](#view-objects)
1. [Indexes](#indexes)
1. [Hidden Indexes](#hindexes)
1. [Custom dashboards configuration](#cdash)
1. [Monitoring configuration](#monitoring)
1. [Bundled Plugins configuration](#plugins-conf)


<a name="globals" />

### Globals

Global for SMG variables are defined as a name -> value pairs where the
name is prefixed with '$' sign. Here is the list of supported global
variables, together with their default values (all of these are
optional):

- **$rrd\_dir**: _"smgrrd"_ - directory where to store your rrd files.
You probably want to change this on any production installation
(for more demanding setups, may want to put that dir on an SSD drive).
That dir must be writabe by the SMG user. This must be defined before
object definitions in the config.

- **$rrd\_dir\_levels**: _None_ - if set, it has to be a list of numbers 
separated with ':' symbol (e.g. "1:1"). In that case SMG will create and 
use sub-dir levels under rrd\_dir (one level per list item) using the MD5
hash value of the object id and respective number of bytes (for each level) 
from that hash as hex strings. Useful if want to keep track of hundreds of 
thousands of rrds where having them all in one dir could be a problem on
some systems. Only set this once (attempt to set it second time in the config
will be ignored). WARNING: Never change the value once you have rrds created,
you will lose the data if you do so.


- **$dash-default-cols**: _6_ - How many "columns" of graphs to display on
the dashboard by default. SMG will insert a new line between graphs at that
"column".

- **$dash-default-rows**: _10_  - How many "rows" of graphs to display on
the dashboard by default. This together with the number of "columns"
determines the "page size" - how many graphs will be displayed on each
graphs page.

- **$auto-refresh-interval**: _300_ - page auto refresh interval (if enabled by
default or via the UI). Set to 0 to completely disable auto-refresh.

- **$default-auto-refresh**: _true_ - whether by default dashboard pages will
reload automatically. This can be toggled on per page basis in the UI.

- **$default-interval** - _60_ - default update interval for objects
not specifying it. This must be defined in the config before object
definitions lacking interval setting (and one can specify it multiple
times, changing the value for subequently defined objects) if one wants
to change it from the default.

- **$default-timeout** - _30_ - default timeout for object fetch
commands (when retrieving data for updates) not specifying it.
This must be defined in the config before object definitions lacking
interval setting (and one can specify it multiple times, changing the
value for subequently defined objects) if one wants to change it
from the default.

<a name="img_dir" />

- **$img\_dir**: _"public/smg"_ - where to output the graphed images.
That dir must be writabe by the SMG user.

- **$url\_prefix**: _"/assets/smg"_ - what is the base url path under which
the image files will be accessible by the browser

- **$rrd\_cache\_dir**: _"smgrrd"_ - sometimes (when wanting to graph an
image from multiple rrd files residing on diff remotes) SMG needs
to download the actual rrd files locally. These rrd files are stored
under the $rrd\_cache\_dir value. That dir must be writabe by the SMG
user.

- **$rrd\_tool**: _rrdtool_ - the rrdtool command to use. Can specify
full path if the version of rrdtool you want to use is not on
the default PATH.

<a name="rrd_socket" />

- **$rrd\_socket**: - _(default is None)_. Otherwise one can specify
a string value like unix:/path/to/socket.file. If specified it will
be passed to the relevant rrdtool update, graph or fetch commands with the
--daemon unix:/path/to/socket.file option. This in turn is
intended for use with rrdcached (a rrdtool daemon used for doing
updates more efficiently). rrdcached is highly recommended on more
demanding setups.

- **$rrdcached\_update\_batch\_size**: - _1_ - if set to more than 1 and
$rrd\_socket is specified SMG will batch updates (with batch size of the value)
and use socat and rrdcached protocol directly for even more efficient updates.

- **$rrdcached\_socat\_command**: - _socat_ - the socat command to use when
flushing batched writes. By default SMG will use _socat_ and expect it to
be in the executables PATH.

- **$rrdcached\_flush\_all\_on\_run**: - _false_ - whether to send FLUSHALL to
rrdcached at the end of every run (only used when doing batched updates via
rrdcached protocol)

- **$rrdcached\_flush\_on\_read**: - _true_ - whether to send FLUSH _file_ to
rrdcached before every read - fetch or graph (only used when doing batched
updates via rrdcached protocol). Either this or $rrdcached\_flush\_all\_on\_run
should be set to true in rrdcached batch update mode. Setting both to true can
hurt efficiency and setting both to false would result in retrieving possibly
stale data.

- **$rrd\_graph\_width**: _607_ - the graph width passed to rrdtool when
graphing

- **$rrd\_graph\_height**: _152_ - the graph height passed to rrdtool
when graphing

- **$rrd\_graph\_font**: _"DEFAULT:0:monospace"_ - the graph font string
passed to rrdtool when graphing

- **$rrd\_graph\_dppp**: _3_ _(advanced)_ - how many data points are
represented in a singe "width" pixel. Used to estimate displayed graph
resolution/step

- **$rrd\_graph\_dppi**: _None_ _(advanced)_ - how many data points can
be represented in a singe image. If this is set **$rrd\_graph\_dppp** is
ignored. Used to estimate displayed graph resolution/step

- **$rrd\_graph\_padding**: _83_ _(advanced)_ - the padding (in pixels)
rrdtool adds to the configured **$rrd\_graph\_width** for the resulting
image size. Used to estimate html cell widths.

- **$rrd\_max\_args\_len**: _25000_ _(advanced)_ - The maximum length
of a rrdtool command. If a resulting command exceeds that the rrdtool
arguments will be passed via stdin instead of command-line args as it is
by default.

- **$monlog\_dir**: _"monlog"_ - the directory where the monitoring
system saves logs with events in json format (one file per calendar date).

- **$monstate\_dir**: _"monstate"_ - the directory where the monitoring
system saves its memory state on shut down.

- **$include**: _(no default value)_ "path/to/some/\*.yml" - whenever
the SMG config parser encounters an $include global it will interpret
its value as a filesystem "glob" (and possibly expand that to multiple
files) and then process each of the files yielded from the glob as
regular config files (these can have more $includes too)

- **$search-max-levels**: _10_ - how many levels (of dotted tokens) to
support in autocomplete. Lower to 2-3 or less if you run a cluster
with hundreds of thousands of objects.

<a name="index-tree-levels">

- **$index-tree-levels**: _1_ - How many levels of indexes to
display by default on the Configured Indexes page. The default value
of 1 means to display only top level indexes, 2 would also display
their children etc. Valid values are between 1 and 5.

<a name="run-tree-levels">

- **$run-tree-levels-display**: _1_ - Same as $index-tree-levels but applies
to monitor state run trees. How many levels of fetch commands to
display by default on the top level Run trees page.


- **$max-url-size**: _8000_ - The maximum url size supported by browsers. SMG
will use POST requests (instead of GET) if a filter URL would exceed that
size. Only draw back is that the resulting URLs will not be shareable. This
needs to be set to around 2000 for IE support, and can be raised to 32k if
one does not care about Android and IE.

<a name="remote" />

- **$remote**: similar to $pre\_fetch $remote is special and is not
a simple name -> value pair. A $remote defines an unique remote **id**
and an **url** at which the remote SMG instance is accessible.
Here is an example remote definition:

<blockquote>
<pre>
- $remote:
  id: another-dc
  url: "http://smg.dc2.company.com:9080"
# slave_id: dc1
# graph_timeout_ms: 30000
# config_fetch_timeout_ms: 300000
</pre>
</blockquote>

> If the optional **slave\_id** parameter is provided it indicates that
this instance is a "worker" in the context of that remote. Its value must
be the id under this instance is configured on the "master". A slave
instance will not load and display the relevant remote instance config
and graphs but will only notify it on its own config changes.

> One can run a setup where the "main" instance (can be two of them,
for redundancy) has multiple remotes configured where the remote
instances only have  the "main" one as configured (for them) remote
(with slave\_id set). With such setup one only needs a  single "beefy"
(more mem) "main" instance which will hold all available across the
remotes objects and the other ones will only keep theirs.

> The **graph\_timeout\_ms** and **config\_fetch\_timeout\_ms** values
allow one to override the global timeouts for garph/monitor state API
calls and config fetch (which can be much slower)

- **$remote-graph-timeout-ms**: 30000 (30 sec) - default timeout when 
requesting graphs (and monitor states) from remote instances. Can be
overridon in each remote definition.

- **$remote-config-fetch-timeout-ms**: 300000 (5 min) - default imeout
when fetching remote config. These can be big so normally thats much
higher than the graph timeout value.

- **$reload-slave-remotes**: _"false"_ - By default SMG will only
notify "master" remote instances (ones defined with slave_id property).
One can override this behavior and make it notify slave instances too
by setting this to "true".
 
- **$proxy-disable**: _"false"_ - by default SMG will link to remote
images via its /proxy/\<remote-id>/\<path-to-image>.png URL which in
turn will proxy the request to the actual remote SMG instance. This
behavior can be disabled by setting the $proxy-disable value to "true".
In that case the end-user will need direct access to the respective
remote instances.

> Note that in production setups where there is already a reverse proxy
serving the static images directly it is recommended to keep
$proxy-disable to "false" (same as omitting it) and then to intercept
the /proxy/\<remote-id> URLs at the reverse proxy and proxy these
directly to the respective SMG instances (at their root URL) instead
of hitting the local SMG one for proxying. Here is how an example
reverse proxy configuration for apache could look like for a remote
named "_some-dc_" where SMG is running on _smg1.some-dc.myorg.com_ and
listening on port 9080 (possibly another reverse proxy there):

<blockquote>
<pre>
        ProxyPass        /proxy/some-dc  http://smg1.some-dc.myorg.com:9080/
        ProxyPassReverse /proxy/some-dc  http://smg1.some-dc.myorg.com:9080/
</pre>
</blockquote>

> Check the [Running and troubleshooting](#running) section for more
details on reverse proxy setup in production.


- **$proxy-timeout**: _30000_ - this option can be used to adjust the
proxy requests timeout (when these are handled by SMG and not the
reverse proxy in front).


<a name="rra_def" />

- **$rra\_def**: - this is another global definition which represents
 an object instead of simple name -> value pair. Whenever rrdtool
 creates a new rrd file it must get a set of definitions for Round
 Robin Archives (RRAs, explained better
 [here](http://oss.oetiker.ch/rrdtool/tut/rrd-beginners.en.html)). A
 $rra\_def has an **id** and a list of RRA definitions under the
 **rra** key. The actual RRA definitions are strings and defined using
 rrdtool syntax. Here is how the default SMG RRA for 1 minute interval
 would look like if defined as $rra\_def:

<blockquote>
<pre>
- $rra_def:
  id: smg_1m
  rra:
    - "RRA:AVERAGE:0.5:1:5760"
    - "RRA:AVERAGE:0.5:5:1152"
    - "RRA:AVERAGE:0.5:30:1344"
    - "RRA:AVERAGE:0.5:120:1440"
    - "RRA:AVERAGE:0.5:360:5840"
    - "RRA:AVERAGE:0.5:1440:1590"
    - "RRA:MAX:0.5:1:5760"
    - "RRA:MAX:0.5:5:1152"
    - "RRA:MAX:0.5:30:1344"
    - "RRA:MAX:0.5:120:1440"
    - "RRA:MAX:0.5:360:5840"
    - "RRA:MAX:0.5:1440:1590"
</pre>
</blockquote>

> Note that normally one does not need to define or use any $rra\_def
objects, the defaults which SMG will pick would work just fine for most
of the cases (these have been inspired by mrtg/cacti). Still there are
some use cases where one wants to use different RRAs - e.g. keep
some important graphs at higher resolutions for longer period
(the draw-back being a bigger RRD file size).


<a name="notify-command">

- **$notify-command** - this defines a named command object to be
executed for delivery of alert notifications (can have many of those).
The command id can then be referenced as "recipient" in
notify-{crit/warn/spike} object/index or global definitions.
Example $notify-command definitions, together with globals referencing
them:

<blockquote>
<pre>
- $notify-command:
  id: mail-people
  command: "smgscripts/notif-mail.sh 'asen@smule.com somebodyelse@smule.com' "
- $notify-command:
  id: pagerduty
  command: "smgscripts/notif-pagerduty.sh"
- $notify-crit: mail-people,pagerduty
- $notify-warn: mail-people
</pre>
</blockquote>

> The actual command gets executed with the following variables
set by SMG in the child process environment:

> - $SMG\_ALERT\_SEVERITY - one of  RECOVERY, ACKNOWLEDGEMENT, ANOMALY,
  WARNING, FAILED, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED

> - $SMG\_ALERT\_KEY - the affected object/var, pre-fetch or global issue
    identification string.

> - $SMG\_ALERT\_SUBJECT - the "subject" for the message to be sent

> - $SMG\_ALERT\_BODY - the "body" of the message to be sent

> Check smgscripts/notif-mail.sh for an example of how this could work

- **$notify-global**: a comma separated list of $notify-command
ids, to be executed on any global SMG errors (usually - overlaps)

- **$notify-crit**: a comma separated list of $notify-command
ids, to be executed on any (global or object-specific) critical errors

- **$notify-fail**: a comma separated list of $notify-command
ids, to be executed on any (global or object-specific) "failed" (i.e.
fetch command failure) errors

- **$notify-warn**: a comma separated list of $notify-command
ids, to be executed on any (global or object-specific) warning errors

- **$notify-anom**: a comma separated list of $notify-command
ids, to be executed on any anomaly (spike/drop) errors. Be warned
that this can get noisy on large setups.

- **\$notify-baseurl**: Base url to be used in alert notifications links,
default is http://localhost:9000 (so you probaly want that set if you
intend to use alert notifications). This can also be pointed to a
different (master) SMG instance URL where the current one is
configured. Set **$notify-remote** to be the id of the current
instance as defined in the master config in that case.

- **$notify-remote**: See $notify-baseurl above, default is none/local.

- **$notify-backoff**: set the default "backoff" period or the interval
at which non-recovered issues alerts are re-sent. Default is "6h".

- **$notify-throttle-count**: Alert notifications support throttling,
you can set the max messages sent during given interval. This sets
the max messages (count) value. The default when not present is
Int.MaxValue which effectively disables throttling.

- **$notify-throttle-interval**: Alert notifications support throttling,
 you can set the max messages sent during given interval (in seconds).
 This sets the interval (default is 3600 or 1h).

- **$notify-strikes**: (default: _3_) - how many consecutive error states to
be considered a hard error and in turn - trigger alert notifications.

- **$cdash**: Defining a custom dashboard. Check for details
[below](#cdash)

<a name="pre_fetch" />

- **$pre\_fetch**: pre\_fetch is special and it is not a simple name ->
value pair. A $pre\_fetch defines an unique **id** and a **command** to
execute, together with an optional **timeout** for the command
(30 seconds by default) and an optional "parent" pre\_fetch id. If
a **pass\_data** property is defined and is set to true the stdout of the
command will be passed to all child commands as stdin. By default
SMG will use the timestamp of the top-level pre-fetch for RRD updates. One
can set the **ignorets** property to ignore the timstamp of the pre-fetch
and use the first child timestamp which doesn't have that property. In addition
pre\_fetch can have a **child\_conc** property (default 1 if not specified)
which determines how many threads can execute this pre-fetch child pre-fetches
(but not object commands which are always parallelized). One can also specify a 
**delay** expressed as a floating point value in seconds. This will cause the
command (and it children) to be executed with a delay according to the specified
number of seconds. If a negative delay is specified the command will be run 
with a random delay between 0 and (interval - abs(delay)) seconds. Pre fetch also
supports **notify-fail** - to override alert recipients for failure and also
**notify-disable**, **notify-backoff** etc. (check [monitoring config](#monitoring)
for details). Here are two example pre_fetch definitions, one referencing the
other as a parent:

<blockquote>
<pre>

    - $pre_fetch:
      desc: "check if localhost is up"
      id: host.host1.up
      command: "ping -c 1 host1 >/dev/null"
      notify-fail: mail-asen, notif-pd
      child_conc: 2
      ignorets: true
      timeout: 5

    - $pre_fetch:
      id: host.host1.snmp.vals
      command: "snmp_get.sh o1 laLoad.1 laLoad.2 ssCpuRawUser.0 ssCpuRawNice.0 ..."
      pre_fetch: host.host1.up
      pass_data: true
      timeout: 30
      delay: 5.5

Alternate (new) syntax:

    - type: pre_fetch
      desc: "check if localhost is up"
      id: host.host1.up
      command: "ping -c 1 host1 >/dev/null"
      notify-fail: mail-asen, notif-pd
      child_conc: 2
      ignorets: true
      timeout: 5

    - type pre_fetch
      id: host.host1.snmp.vals
      command: "snmp_get.sh o1 laLoad.1 laLoad.2 ssCpuRawUser.0 ssCpuRawNice.0 ..."
      pre_fetch: host.host1.up
      pass_data: true
      timeout: 30
      delay: 5.5

</pre>
</blockquote>

> As explained in the [concepts overview](#concepts-pre_fetch) SMG
RRD objects can specify a pre\_fetch command to execute before their
own command gets executed (for the current interval run). That way
multiple objects can be updated from given source (e.g. host/service)
while hitting it only once per interval. Pre\_fetch itself can have
another pre\_fetch defined as a parent and one can form command trees
(or DAGs) to be run top-to-bottom (stopping on failure).

> Note that the "run tree" defined in this way must not have cycles
which can be created in theory by circularly pointing pre\_fetch parents
to each other (possibly via other ones). Currently SMG will reject
the config update if circular parent depenencies in the run-tree are
detected (detection is simply having a hard limit of max 10 parent
levels when constructing the run trees).

<a name="rrd-objects" />

<a name="interval_def" />

- **$interval\_def**: similar to pre\_fetch  interval\_def is special and
it is not a simple name -> value pair. It has a mandatory **interval** property
specifying the interval (in seconds) for which this applies and optional
**threads** property specifying max number of threads (default is 4 and if set to 0
it will be dynamically set to the number of cpu cores available) and and a **pool**
property specifying the type of thread pool - one of FIXED (default) and
WORK_STEALING. Examples:

<blockquote>
<pre>

    - $interval_def:
      interval: 60
      threads: 20
      pool: FIXED

    - $interval_def:
      interval: 200
      threads: 4
      pool: WORK_STEALING

Alternate (new) syntax:

    - type: interval_def:
      interval: 60
      threads: 20
      pool: FIXED

    - type: interval_def:
      interval: 200
      threads: 0 # will use num cpu cores
      pool: WORK_STEALING

</pre>
</blockquote>

### RRD objects

RRD objects usually represent the majority of the SMG config. These
define a rrd file to be updated, its structure, the interval on
which we want to update it and the command to use to retrieve the
values to update. A rrd object is represented by a yaml structure
which could look like this:

<pre>
- id: host.localhost.sysload                            # rrd object id
  command: "smgscripts/mac_localhost_sysload.sh"        # mandatory command outputting values
  timeout: 30                                           # optional - fetch command timeout, default 30
  title: "Localhost sysload (1/5/15 min)"               # optional title - object id will be used if no title is present
  interval: 60                                          # optional - default is 60
  data_delay: 0                                          # optional - default 0
  delay: 0.0                                            # optional - default 0.0
  rrd_type: GAUGE                                       # optional - default is GAUGE. Can be COUNTER etc (check rrdtool docs)
  rrd_init_source: "/path/to/existing/file.rrd"         # optional - if defined SMG will pass --source <val> to rrdtool create
  stack: false                                          # optional - stack graph lines if true, default - false
  pre_fetch: some_pf_id                                 # optional - specify pre_fetch command id.
  notify-fail: mail-asen,notif-pd                       # optional - sent command failures to these recipients (see notify- conf below)
  labels:                                               # optional - arbitrary map of key/valu labels, useful for filtering
    foo: bar                                            #            and grouping by
  vars:                                                 # mandatory list of all variables to graph
    - label: sl1min                                     # the variable label.
      min: 0                                            # optional - min accepted rrd value, default 0
      max: 1000                                         # optional - max accepted rrd value, default U (unlimmited)
      maxy: 1005                                        # optional - use this te set upper limit on the displayed graph (similar to maxy index option)
      mu: "lavg"                                        # optional - measurement units, default - empty string
      lt: "LINE1"                                       # optional - line type, default is LINE1
      alert-warn-gt: 8                                  # optional - monitoring thresholds, details below
    - label: sl5min                                     # another variable def
    - label: sl15min                                    # a 3d variable def
</pre>

Most properties are optional except:

- **object id** - in the old syntax that would be the key after the dash
defining the start of the object, *host.localhost.sysload* in the example.
The new syntax explicitly sets the id key/value. It must be unique for the
local SMG instance and consist only of alpha-numeric characters
and the '\_', '-' and '.' symbols. By convention, ids should be defined
like **&lt;class>.&lt;subclass>\[.&lt;subclass>\...].&lt;object>**,
e.g. _host.o1.cpu_ or _host.o1.diskused.var_. Following that convention
is not enforced by SMG but using it helps it discover "automatic
indexes" and also makes it much easier to write [index (filter)
definitions](#indexes) and in general - navigate around SMG.

- **command**: is a mandatory property and its value must be a
system command (string) which (retrieves and) outputs the numeric values
we want tracked, each on a separate line. Note that SMG only cares about
the first N lines of the output (where N is the number of vars,
see below) from the script and will ignore excess lines (this is
for compatibility with mrtg scripts which output uptime and hostname
after values). If the command ouptuts less lines than expected or
these are not numeric values, an error will be generated and NaN
values will be recorded in the RRD file time series.

<a name="obj-vars">

- **vars**: - mandatory list of yaml objects (string -> string maps).
The number and order of var objects determines the rrd structure and
how many lines of output from the external _command_ will be considered.
Each var itself supports the following properties where only label is
mandatory (technically - even label is not mandatory but at least one
key -> value must be present):

    - **label**: - the label to use for this variable in the graph
    legend

    - **mu**: ("measurement units") - what string to display after the
    number (avg/max etc) values in the graph legend, e.g. "bps" (for
    bits per secod). rrdtool (and thus SMG) will automatically use
    scale modifiers like M (for mega-), m (for milli-) etc. so bps would
    become Mbps for megabits per second.

    - **min**: (default _0_) - minimal accepted rrd value. Values below
    that will be ignored and NaN will be recorded

    - **max**: (default _U_ for unlimited) - maximal accepted rrd value.
    Values above that will be ignored and NaN will be recorded instead.
    This is rarely needed for GAUGE type graphs but is almost **always**
    needed for COUNTER graphs. rrdtool will use the max value to detect
    counter overflows where the delta is usually resulting in a huge
    integer (if unsigned, otherwise - negative) value. If that value is
    above the max rrdtool will ignore it and record a NaN.

    - **maxy**: (default _None_ - auto scaling) - set the upper limit
    on the displayed graph.

    - **lt**: (line type, default is _LINE1_) - the line type to use
    when graphing. Check rrdtool documentation for supported line
    types (e.g. AREA etc).

    <a name="obj-vars-cdef">

    - **cdef**: - one can provide a rrdtool cdef /
    [RPN expression](http://oss.oetiker.ch/rrdtool/tut/rpntutorial.en.html)
    and SMG would graph the result of that expression
    instead of the original value. Inside the expression the actual
    rrd value can be referenced using the special '_$ds_' string.
    For example to multiply the actual RRD value by 8 before graphing
    one would use the following cdef value: _"\$ds,8,*"_. This specific
    example is useful when graphing network traffic counters which are
    reported in bytes but we prefer to have them graphed in bits (per
    second)

    - **alert-\***: - these are multiple properties which define
    monitoring alert thresholds (e.g. alert-wart-gt, alert-crit-lt,
    etc.) check [monitoring config](#monitoring) for details.

    - **notify-\***: - these are multiple properties which define
    monitoring alert notifications. Check [monitoring config](#monitoring)
    for details.

Note that changing the number of vars when the rrd object already
exists is not supported and will cause update errors, and changing
their order will result in messed up data. It is still possible
to make such a change outside SMG using rrdtool though.

The following properties are all optional.

- **interval**: - (default - _60_ seconds) - every rrd object has a an
interval associated with it - determining how often the data will be
retrieved from the monitored service and updated in the RRD file.

- **data\_delay** - (default - _0_ seconds) - sometimes services report
their data with certain delay (e.g. a CDN API may report the traffic
used with 15 min delay). Set that value to the number of seconds data is
delayed with and on update SMG will subtract that many seconds from the
fetch/update timestamp. This was named **dataDelay** in older versions.

- **delay** - (default - _0.0_ seconds) - If an object has a delay specified
its fetch command will not run immediately when its turn comes but after the
specified amount of seconds. If a negative delay is specified the command
will be run with a random delay between 0 and (interval - abs(delay)) seconds.

- **title**: - free form text description of the object. If not
specified, the object id will be used as a title.

- **rrd\_type**: (default - GAUGE) - can be COUNTER, DERIVE etc, check
rrdtool docs for other options. Note that originally SMG used rrdType
for this property which is inconsistent with the rest. SMG will still
try to read rrdType if rrd\_type is not present, for backwards
compatibility.

- **rrd\_init\_source**: (no default) - if defined SMG will pass
--source _val_ to rrdtool create. This can be used to rebuild some
rrd using different step/RRAs etc. E.g. update the conf specifying
rrd_init_source: object.id.old, then rename the actual
rrd file from object.id.rrd to object.id.old. On the next run
SMG will re-create the rrd using the newly specified config and
populate the data from the pre-existing values. Check rrdtool docs
for more information and caveats (Note: this feature requires
recent rrdtool version supporting the --source option, 1.5.5+ is
known to work)

- **stack**: (default - false) - if set to true - stack graph lines on
top of each other.

- **pre\_fetch**: (default - None) - specify a [$pre\_fetch](#pre_fetch)
 command id to execute before this object command will be run.

- **rra**: (default - None) - specify a [$rra\_def id](#rra_def) to use
for this object. By default SMG will pick one based on object interval.

- **notify-xxx**: - these are multiple properties which define
monitoring alert notifications. Check [monitoring config](#monitoring)
for details. Note that from the per-leve specifiers only notify-fail
is relevant at this level.

- **labels**: (default - empty map) - a map of key/vaule pairs useful for
filtering and grouping by.

<a name="rrd-agg-objects" />

### Aggrgegate RRD objects

In addition to the "regular" RRD Objects described above, one can define
"aggregate" RRD Objects. Simiar to the aggregate functions which can be
applied on multiple View objects, the aggrgeate RRD objects represent values
produced by applying an aggregate function (SUM, AVG, etc) to a set of
update objects. The resulting value is then updated in a RRD file and the
object also represents a View object subject to display and view
aggregation functions. An aggrgegate RRD object is defined by prepending a
'+' to the object id. Example:

<pre>
- id: +agg.hosts.sysload                                # aggregate rrd object id, must start with + char
  op: AVG                                               # mandatory aggregate function to apply
  ids:                                                  # mandatory list of object ids
    - host.host1.sysload                                # regular rrd object id, currently must be defined before this object in the config
    - host.host2.sysload                                # regular rrd object id, currently must be defined before this object in the config
  interval: 60                                          # optional - default is the interval of the first object listed in the ids list
  title: "Localhost sysload (1/5/15 min)"               # optional title - object id will be used if no title is present
  rrd_type: GAUGE                                       # optional - if not set, the rrd_type of the first object will be used
  rrd_init_source: "/path/to/existing/file.rrd"         # optional - if defined SMG will pass --source <val> to rrdtool create
  stack: false                                          # optional - stack graph lines if true, default - false
  notify-fail: mail-asen,notif-pd                       # optional - sent command failures to these recipients (see notify- conf below)
  labels:                                               # optional - arbitrary map of key/valu labels, useful for filtering
    foo: bar                                            #            and grouping by
  vars:                                                 # optional list of all variables to graph. If not set the first object vars list will be used.
    - label: sl1min                                     # the variable label.
      min: 0                                            # optional - min accepted rrd value, default 0
      max: 1000                                         # optional - max accepted rrd value, default U (unlimmited)
      mu: "lavg"                                        # optional - measurement units, default - empty string
      lt: "LINE1"                                       # optional - line type, default is LINE1
      alert-warn-gt: 8                                  # optional - monitoring thresholds, details below
    - label: sl5min                                     # another variable def
    - label: sl15min                                    # a 3d variable def
</pre>

As mentioned in the yaml comments above, some of the properties of the aggregate object will be
assumed from the first object vars list, unless explicitly defined. It is up to the config to
ensure that the list of objects to aggregate is compatible (and meaningful).

<a name="view-objects" />

### View objects

As mentioned in the [concepts overview](#concepts-view-objects)
every RRD object is implicitly also a View object. Additional View
objects can be defined in the configuration by referencing existing
RRD objects too. These have two main purposes:

- to be able to graph only a subset of the RRD object vars and/or
change their order by specifying the graphed vars as a list of
indexes in the RRD object vars (see [gv](#gv) below). Here is
an example configuration for such an object (this will graph only
the second var from the list of the referenced object vars).

<pre>
    - host.localhost.sysload.5m:                             # View object id
      title: "Localhost sysload - 5min only"                 # optional title - object id will be used if no title is present
      ref: host.localhost.sysload                            # object id of an already defined rrd object
      stack: false                                           # optional - stack graph lines if true, default - false
      gv:                                                    # a list of integers ("graph vars")
        - 1                                                  # index in ref object vars list
</pre>

- to be able to graph a value calculated from the RRD object's values
using [cdef\_vars](#cdef_vars). Here is an example definition of
such an object which is referencing another object with 2 vars (not
visible here) - one for varnish req/sec and the other for varnish
cache hit/sec. The View object below defines a single cdef var(line)
calculating its values by evaluating the cdef expression  (calculating
cache hit % in this case) [explained below](#cdef_vars-cdef)

<pre>
    - host.va1.varnishstat.hitperc:
      title: "va1 - varnishstat: cache_hit/client_req %"
      ref: host.va.varnishstat.client_req_hitttl
      cdef_vars:
        - label: "hitperc"
          mu: "%"
          cdef: "$ds1,100,*,$ds0,/"
</pre>

One should not set both gv and cdef\_vars on the same View object. If
one does that the gv values will be ignored. Note that technically it
is possible to do what gv does using just cdef\_vars except
that gv provides a simpler way to just select a subset or reorder graph
lines.

View objects support the following properties:

- the **object id**: This is the unique object view id (must not
conflict with other view or rrd objects). Check
[RRD Objects](#rrd-objects) for more info on allowed and good object
ids.

- **ref**: mandatory reference (string) to a RRD object id

- **title**: - free form text description of the object. If not
specified, the ref object title will be used.

<a name="gv" />

- **gv** (graph vars) - This is a list of integers representing 0-based
indexes in the ref object [vars](#obj-vars) list. For example if the
ref object has 3 vars and we want to graph the third and first in that
order we would use the following gv definition:

<pre>
    ...
    gv:
      - 2
      - 0
</pre>

<a name="cdef_vars" />

- **cdef\_vars** - this defines a list of variables very similar to the
referenced RRD object [vars](#obj-vars). It supports the same properties
and the main difference is how the <a name="cdef_vars-cdef" /> **cdef**
property is treated. On a RRD object [cdef](#obj-vars-cdef) can only
reference the variable its defined on by using the _$ds_ special string.
In cdef\_vars we can reference all vars in the ref RRD object in a single
[RPN expression](http://oss.oetiker.ch/rrdtool/tut/rpntutorial.en.html)
and produce a single graph line from multiple vars. The ref object
variables are referenced in the expression by their 0-based index with
$ds prefix - _\$ds0_, _\$ds1_, _\$ds2_ etc. In the above example we have

<pre>
      ...
      cdef_vars:
          ...
          cdef: "$ds1,100,*,$ds0,/"
</pre>

> The cdef expression can be translated as (form right to left): _Divide
the value of ( the product of the 2nd ($ds1) var with 100 ) by the
value of the 1st ($ds0) var_. Or _$ds1 \* 100 / $ds0_ in more
conventional notation. In our case the $ds0 represents "requests/sec"
and $ds1 represents "cache hits/sec". So that expression is calculating
the cache hit % (from all requests). Rddtool has great documentation on
[RPN expressions](http://oss.oetiker.ch/rrdtool/tut/rpntutorial.en.html)
and I strongly recommend reading that for anyone who wants to write cdef
expressions.


<a name="indexes" />

### Indexes

In SMG an "index" represents a named "filter" which can be used to
identify and display a group of graphs together. Also an index can
define a parent and child indexes, so one can define a tree-like
navigational structure using indexes. If an index does not have a
parent index it is considered a "top-level" index. All top-level
indexes are displayed on the SMG main page (by remote), together with
their first-level child indexes.

Index objects are defined in the yaml along with the RRD and View
objects and their object ids start with the _'^'_ special  symbol.

Here is an example Index definition:

<pre>
    - id: ^hosts.localhost       # index id, must start with a ^ char
      title: "localhost graphs"  # optional - id (sans the ^ char) will be used if not specified
      cols: 6                    # optional (default - the value of $dash-default-cols) how many coulmns of graph images to display
      rows: 10                   # optional (default - the value of $dash-default-rows) how many rows (max) to display. excess rows are paginated
      px: "host.localhost."      # optional filter: rrd object id prefix to match. Ignored if null (which is the default).
      sx: ...                    # optional filter: rrd object id suffix to match. Ignored if null (which is the default).
      rx: ...                    # optional filter: regex to match against rrd object id. Ignored if null (which is the default).
      rxx: ...                   # optional filter: exclude objects which ids match the supplied regex. Ignored if null (which is the default).
      trx: ...                   # optional filter: regex to match against object text representation (including title, var names etc). Ignored if null (which is the default).
      parent: some.index.id      # optinal parent index id. Indexes without parent are considered top-level
      children:                  # optional list of child index ids to display under this index
        - example.index.id1
        - example.index.id2
        - ...
      remote: "*"                # optional - index remote, default is null, use "*" to specify index matching all remotes.
                                 # Individual remote instances can be specified by using their comma-separated ids.
</pre>

Index objects support the following properties:

- the **index id**: This is the unique index id, starting with the ^ symbol (must not
conflict with other index ids).

- **title**: - free form text title of the index. If not
specified, the id (sans the ^ char) will be used.

- **desc**: - optional free form text description of the index
(displayed together with title on index pages).

- **cols**: (default - the value of the $dash-default-cols global) -
optional number specifying in how many "columns" to display the rows
of graphs. Note that this sets a max limit (and a hard "line break"),
chances are that on smaller screens your browser will still wrap graph
"rows" as needed for display.

- **rows**: (default - the value of the $dash-default-rows global) -
optional number specifying how many rows (each having max _cols_ graphs)
to display, effectively setting the number of graphs SMG will display on
a single page of results.

<a name="period" />

- **period**: (default - 24h) - a SMG "period string" (or number -
in seconds) specifying the displayed graphs time range starting point
relative to "now". The period string format is inspired by
[rrdtool time offset](http://oss.oetiker.ch/rrdtool/doc/rrdfetch.en.html#ITIME_OFFSET_SPECIFICATION)
(technically it is a subset of it) but you generally
set a point in time (relative to now) since which you want to see
graphs. It is generally a number followed by an optional single char
time specifier which can be one of the following:
    - _y_ - for years,
    - _m_ - this is currently used for both minutes and months,
    If the number is less than 13 it will be considered to be a month
    otherwise - minutes. Use M to force specifying minutes.
    - _d_ - for days
    - _h_ - for hours
    _ _M_ - for minutes
    - (None, just a number) - assumed to be seconds.
The end point of the graphs can be set to be different than
"now" by using a "period length" (**pl**)
[graph option](#graph-options).

- **agg\_op**: (default - None) - A SMG aggregate operation string (one
of _GROUP_, _STACK_, _SUM_, _SUMN_, _AVG_, _MAX_ or _MIN_ ). If that is
specified SMG will directly apply the respective
[aggregate operation](#concepts-aggregate-functions) to the
graphs resulting from the filter, before display.

- **xagg**: (default - false) - By default, when SMG is executing
aggregate operations it will execute them "per-remote" - result
will have the aggrgeate graphs split and displayed by the
remote instance they "live" in. It is possible to request aggregation
across all remotes too - by setting the xagg value to _true_. Note
that this is a relatively expensive operation - SMG has to download
all neded remote RRD files to be able to produce a single graph from
them so results can be expected to be a bit slower than normally.

- **parent**: (default None) - specifying a "parent" index id. Indexes
not having a parent id are considered to be "top-level" and are
displayed on the main index page (together with their first-level
children). If set to some parent id this index will be displayed
in the childs list of the parent.

- **children**: - optional yaml list of strings pointing to other
index ids. The indexes pointed by these ids will be displayed as
children for this index. Note that the target indexes do not need
to have their parent pointed to this index. I.e. an index can have
multiple parents that way.

The remaining index properties represent a filter (with graph options).
These deserve thir own subsection:

<a name="filters" />

#### Filters and graph options

A filter (and its graph options) is configured as part of an
index definition using the following properties (along with the rest
index properties):

- **trx** (Text Regex) - when set, only objects with titles, object ids,
graph labels or measurement units matching the specified regular
expressions will be matched by the filter.

- **px** (Prefix) - when set, only object ids having the specified
prefix string (starting with it) will be matched by the filter.
Note that this is not a regexp but a direct string comparison.

- **sx** (Suffix) - when set, only object ids having the specified
suffix string (ending with it) will be matched by the filter.
Note that this is not a regexp but a direct string comparison.

- **rx** (Regex) - when set, only object ids matching the specified
regular expressions will be matched by the filter.

- **rxx** (Regex Exclude) - when set, only object ids NOT matching the
specified regular expressions will be matched by the filter.

- **prx** (Parent regex filter) - when set, only object with parent/pre\_fetch
ids matching the specified regular expressions will be matched by the filter.

- **lbls** (Labels expression filter) - when set, only objects which have
object labels matching the provided label expression will be matched by the filter.
Note that object labels are a new feature (and separate from object vars
graph display labels).

    Label expressions can consist of:
    - just a label name - in which case object labels having that label name will
match regardless of their label value
    
    - label-name=label-value - both the name and the value must mutch
    
    - label-name!=label-value - object must have label with the specified name but
    value must be different than label-value
    
    - label-name=~label-value - label value is treated as regex and object's value
    for the same label must match that.
    
    - label-name!=~label-value - label value is treated as regex and object's value
    for the same label must NOT match that.

    All of these can be prepended with an ! to inverse their effect (i.e. whetehr to
include or exclude matching objects). The labels filter supports multiple
expressions sepaarted by space. This means that spaces in label value filters are
not currently supported (as a workaround - use regex and \s). 
  

- **remote** (Remote instance, by default - None, meaning local).
This should be either set to "\*" (quoted in the yaml as the asterisk
is special char) which means that this filter should filter across all
available remotes or not set at all (meaning "local" filtering).
When SMG displays remote index it will automatically populate its
value when displaying (note that the "remote" definition is only
meaningful in the context of another remote which references the former
as some _remote id_).

- **dhmap** - (TODO) when set to true, SMG will not display Monitoring
heatmap for that index.

- **alerts** - alert configurations defined for all objects matching
this index. Check [monitoring configuration](#monitoring) for more
details.


<a name="graph-options" />

The remaining properties represent "Graph Options" - generally
specifying some non-default options to display graphs. These are
rarely specified in index definitions but more often - come
from UI requests.

- **step**: (default None, which means automatic) - an integer (in
seconds) specifying how many seconds should a data point in the graph
represent. This is rarely needed as SMG (rrdtool actually) will
pick an optimal step (a.k.a. resolution) depending on the requested
period and available RRAs.


- **pl**: ("period length", default None) - this is a string or number
following SMG [period string](#period) format. It sets a time range
(length) which we want the produced graphs to cover (still starting at
the starting point defined in **period** parameter). So for example
you could say "give me graphs starting 24h (period param) ago and
covering 6h (pl param)". Note that this is rarely as useful as it may
seem at first sight. As time passes older RRD/RRA data gets averaged
and loses detail, so if you decide that you want a graph covering 6h
but starting a year ago, chances are that you will see flat
horizontal lines representing the average values for the object vars
for the given 6 hours.

- **dpp**: (disable period-over-period, default false) - by default
SMG will plot two lines for every var in the graph - one (solid) for
the values in the period and another (same color but dotted) - for
the values covering the previous period (of same size). If desired
one can disable that behavior by setting dpp to _true_

- **d95p**: (disable 95%-ile ruler, default false) - by default
SMG will plot a horisontal flat line ("ruler") at the calculated
maximum 95%-ile value (basically, removing the top-5% values,
considered outliers). If desired one can disable the plotting of that
line by setting d95p value to _true_

- **maxy**: (maximum Y-value, default None) - a number setting a hard
max limit on the Y-axis value of the displayed graphs.

- **xsort**: (integer, default 0) - When different than 0 SMG will
treat that as a request to sort the outputted (on the page) graphs
by the average value of the var which (1-based) index is equal to
xsort. E.g set to 1 to sort by the first variable, 2 for the second
etc. Graphs which have less variables than the requested xsort index
will remain unsorted. Sorting is also described
[here](#concepts-sorting).


<a name="hindexes" />

### Hidden Indexes

Only difference in defining a hidden index from a regular one is that
it has a '~' character in the beginning of the index object id (instead
of '^') and the only difference in behavior is that the hidden one is
not displayed on the index page(s).

Currently hidden indexes are only useful in the context of monitoring -
to define a group of objects for which in turn to define alert
thresholds, but not necessarily clutter the main page with all
of the groups.

<a name="cdash" />

### Custom dashboards configuration

Custom dashboards are defined in the yaml configuration using the
**$cdash** global variable.

The $cdash keyword defines a yaml map which must have an unique **id**
property, an optional **title** and a list of **items** of various
types. All item types have some common set of properties - an unique
**id**, an optional **title**, **width** and **height** properties.The
other mandatory propert is the **type** which can be one of the
following:

- *IndexGraphs* - the graphs produced by some defined in smg index. This
item type requires an **ix** property specifying the index id. It also
supports **offset** and **limit** properties in the list of graphs
(default is to show all).

- *IndexStates* - svg heatmaps representing index states. Indexes are
specified using the **ixes** propery which is a list of index ids. The
svg image width is specified via the **img\_width** property.

- *MonitorProblems* - current problems as visible on the monitor page.
Supports customizing what is displayed using **ms** (minimum alert
level) filter and also **soft** and **slncd** filter flags.

- *MonitorLog* - recent monitor/log entries. Supports customizing what
is displayed using **ms** (minimum alert level) filter and also
**soft** and **slncd** filter flags.

- *Plugin* - plugins can implement custom dashboard items and using
custom (plugin-specific) configuration.

- *External* - and external web page displayed in an iframe. Requires
an **url** parameter and a separate **fheight** (frame height)
property from the standard **height**.

- *Container* - this is a special type of item which is a container for
other items specified as a list under the **items** property.

Example:

    - $cdash:
      id: noc
      title: NOC
      items:
        - id: alerts
          title: Active alerts
          type: MonitorProblems
          width: 350
          height: 600
        - id: alert-log
          title: Alert Log
          type: MonitorLog
          width: 350
          height: 600
          limit: 50
          ms: WARNING
        - id: jmx.premote-graphs
          type: IndexGraphs
          title: jmx.premote Graphs
          width: 700
          height: 600
          ix: jmx.premote
          limit: 2
        - id: group1
          type: Container
          width: 700
          height: 800
          items:
          - id: index.states
            title: Index States
            type: IndexStates
            width: 450
            height: 500
            img_width: 300
            ixes:
              - jmx.premote
              - localhost
          - id: calc-netext
            type: Plugin
            width: 700
            height: 300
            plugin_id: calc
            ix: some.id
        - id: google
          title: External web page
          type: External
          width: 800
          height: 620
          fheight: 600
          url: https://google.com/



<a name="monitoring" />

### Monitoring configuration

Every object variable (mapping to a graph line) can have zero or more
alert configurations applied. Each alert config is a key -> value pair
where the key is a special keyword recognized by SMG and the value
usually represents some alert threshold. Currently the following
alert keywords are defined:

    alert-warn: NUM      # same as alert-warn-gte
    alert-warn-gte: NUM  # warning alert if value greater than or equal to NUM
    alert-warn-gt: NUM   # warning alert if value greater than NUM
    alert-warn-lte: NUM  # warning alert if value is less than or equal to NUM
    alert-warn-lt: NUM   # warning alert if value is less than NUM
    alert-warn-eq: NUM   # warning alert if value is equal to NUM
    alert-crit: NUM      # same as alert-crit-gte
    alert-crit-gte: NUM  # critical alert if value greater than or equal to NUM
    alert-crit-gt: NUM   # critical alert if value greater than NUM
    alert-crit-lte: NUM  # critical alert if value is less than or equal to NUM
    alert-crit-lt: NUM   # critical alert if value is less than NUM
    alert-crit-eq: NUM   # critical alert if value is equal to NUM
    alert-p-<pluginId>-<checkId>: # configure a plugin-implemented check for the value

The built-in "mon" plugin implements the following three checks

    alert-p-mon-anom: "" # anomaly alert - detecting unusual spikes or drops
                         #   it accepts a string with 3 values separated by ":"
                         #   the default value (when empty string is provided) is
                         #   "1.5:30m:30h" which means 1.5 (relative) change
                         #   in the last 30 minutes period compared to the previous
                         #   30h period.
    alert-p-mon-pop: "..." # period over period value check - detecting if the current
                         #   value has changed with certain thresholds over the same value
                         #   some period ago. It accepts a string with 4 values separated
                         #   by ":".
                         #   The first value is a period and an optional resolution
                         #   separated by "-". E.g. "24h-1M" means compare with value 24 hrs
                         #   ago over a 1 minute average. If the -1M part is omitted the
                         #   object interval will be used as resolution (that would be the
                         #   highest available resolution in the RRD file).
                         #   The second value is the comparison operator - one of lt(e),
                         #   gt(e) or eq.
                         #   The third and fourth values are the warning and critical
                         #   thresholds of change.
                         #   E.g. to define an warning alert if some value drops below 0.7
                         #   from yesterday and a critical alert if the value drops below 0.5
                         #   from yesterday, at a 5min-average resolution, one can use the
                         #   following config string: "24h-5M:lt:0.7:0.5"
                         #   Both warning and critical thresholds are optional, e.g. use
                         #   something like "24h:lt:0.7" to set only a warning threshold and
                         #   something like "24h:lt::0.5" to set only critical threshold.
    alert-p-mon-ex: "..." # "Extended" check, supporting some special use cases, mainly related
                         #   to using different data resoulution than the update inteval (e.g.
                         #   to check the hourly average of given value despite the value being
                         #   updated every minute. Format is
                         #   "<step>:<op>-<warn_thresh>:<op>-<crit_thresh>[:HH_MM-HH_MM[*<day>],...]"
                         #   step is the time resolution at which we want the current value fetched
                         #   op is one of gt, gte, eq, lte, lt
                         #   warn_thresh and crit_thresh are the respective warning and critical
                         #   threshold numbers.
                         #   The final portion is optional and is a comma separated list of time
                         #   period specifications. Time period is specified by setting time of day
                         #   and/or day of week (first 3 letters from English weekdays) or month (number),
                         #   separated via *. The time of day is defined as a start and end (separated
                         #   by -) hour and minute (separated by _). The check can only trigger alerts
                         #   when the current time is within the time of day (if specified) and day of
                         #   week/month (if specified)

Check [here](#concepts-anomaly) for more details on how
alert-p-mon-anom/anomaly detection works.

In addition to alert-\* properties one can also define the following
notify- settings, specifying a list of "recipients" ($notify-command
ids) to be exectuted on "hard" errors (and recoveries) at the
appropriate severity level (crit/warn/spike), the special
notify-disable flag explicitly disabling notifications for the
applicable object var or the notify-backoff value specifying
at what interval non-recovered object alert notifications
should be re-sent. The notify-strikes value determines how many
consecutive error states to be considered a hard error and in turn -
trigger alert notifications.

    notify-crit: notify-cmd-id-1,notify-cmd-id-2,...
    # (Not relevant for var states) notify-fail: notify-cmd-id-1,notify-cmd-id-2,...
    notify-warn: notify-cmd-id-1,notify-cmd-id-2,...
    notify-anom: notify-cmd-id-1,notify-cmd-id-2,...
    notify-disable: true
    notify-backoff: 6h
    notify-strikes: 3

> In order to disable fetch error notifications for given object one must
set "_notify-disable: true_" at the object level. Pre-fetch commands support
notify-disable too.

> When multiple conflicting notify-strikes values apply, SMG will
use the minimal from these. When multiple conflicting notify-backoff
values apply, SMG will use the maximal from these. Any applicable
notify-disable set to true will result in disabled notifications.

Currently there are two ways to apply alert/notify configs to any given
object variable:

- Inline with the object variable as part of the variable definition
properties. E.g the following will define thresholds for
two variable labelled sl1min and sl5min with values 10/8 which if
exceeded will generate a "warning" alert. The sl5min one will also
trigger the (defined elsewhere) notify-command with id mail-on-warn
where the sl1min value will not trigger notification commands.

<pre>
  ...
  vars:
    - label: sl1min
      ...
      alert-warn-gt: 10
      notify-disable: true
    - label: sl5min
      ...
      alert-warn-gt: 8
      notify-warn: mail-on-warn
      notify-backoff: 1h
</pre>

- As part of indexes (including hidden indexes) under the special
alerts property. Examples:

<pre>
  ...
  notify-fail: ...
  notify-strikes: 5
  alerts:
   - label: sl1min   # any objects matching the index filter and also matching the sl1min label
     alert-warn-gt: 3
     notify-warn: mail-on-warn
     alert-crit-gt: 8
     notify-crit: mail-on-crit
   - label: 1         # when number - it will be used as a 0-based index in the variables array
     alert-warn-gt: 3
     alert-crit-gt: 8
   - label: scur
     alert-p-mon-anom: ""
</pre>

The alerts property is an array of yaml objects each specifying a "label"
and one or more alert thresholds or notify- properties. If the label
is an integer number it will be interpreted as an index in the list of
variables of the objects matching the index filter. For example one
can define default anomaly (spike/drop) detection on all objects
using the following config (just add more alerts defs if there are
objects with more than 8 vars):

<pre>
- ~all.alert.spikes:
  # empty filter means match all
  alerts:
    - label: 0
      alert-p-mon-anom: ""
    - label: 1
      alert-p-mon-anom: ""
    - label: 2
      alert-p-mon-anom: ""
    - label: 3
      alert-p-mon-anom: ""
    - label: 4
      alert-p-mon-anom: ""
    - label: 5
      alert-p-mon-anom: ""
    - label: 6
      alert-p-mon-anom: ""
    - label: 7
      alert-p-mon-anom: ""
</pre>


<a name="plugins-conf" />

## Bundled Plugins configuration
 
- **calc** - exposes UI for graphing the result of arbitrary math expressions
Conf file (smgconf/calc-plugin.yml) supports pre-defined expressions which
will show up in the UI for easy access. Example

<pre>
     calc:
       expressions:
         - expr_id:
            title: "Test expression"
            expr: "localhost.sysload[0] + localhost.dummy[1]"
            period: 24h
            step: 60
            maxy: 1000
            dpp: off
            d95p: off
</pre>

- **cc** - "Common Commands" plugin, exposes commands to use in the regular
object commands. Subcommands:

    - :cc csv
    <pre>
       :cc csv parse [parse opts] [format]
         -d|--delim &lt;char> (default ',')
         -nh|--no-header  - csv has no header row
         -sh|--strict-header - if set parse will abort on duplicate or missing header forgiven column
         -h|--headers hdr1,hdr2,...  - set custom header names based on position
        [format] (default - DEFAULT) - apache commons csv pre-defined format name (e.g. EXCEL)
    
       :cc csv get [get opts] &lt;row selectors (k=v)> &lt;val selectors>
         -e0|--empty-as-0 - if set non existing/empty values will return "0.0"
         -eN|--empty-as-nan - if set non existing/empty values will return "NaN"
        &lt;row selectors> - col1=val1 col2=val2
           col is either a number (0-based column index) or a column header name
           val is the value which the row must have in the respective column
             if val starts with '~' it will be treated as regex
             if val start with '!' the mathc is inverted (i.e. the row must not have that value)
             use !~... for negative regex match
        &lt;val selectors> - list of zero-based column indexes or column header names
        In both cases if a column header name is a number it can be "quoted" in the definition so
        that it is not treated as column index
     
       :cc csv pget [get opts]
         parse the csv using default parse options and get value using provided get options in one shot
    </pre>
       
    - :cc kv
    <pre>
        :cc kv parse [opts]
         -d|--delim &lt;str> (default '=')
         -n|--normalize (default - false) - convert kb/mb/gb suffixes and strip surrounding whitespace
        
        :cc kv get [opts] &lt;key1> &lt;key2...>
         -d |--default &lt;str> (default - None) - return the default value if key is not found
        
        :cc kv pget [get opts] &lt;key1> &lt;key2>
    </pre>
    
    - :cc ln
    <pre>
        :cc ln [opts] index1 [index2...]
           -s &lt;separator_regex> | --separator &lt;separator_regex> (default is any whitespace - \s+)
           -js &lt;join_str> | --join-separator &lt;join_str> (default is space)
           -jn | --join-new-line
        :cc ln -s ", " 1
        split a line using the separator (regex) string and output the specified 1-based elements
        separated by space. 0 means to output the entire input line
    </pre>

    - :cc map
    <pre>
        :cc map k1=v1 k2=v2 [default]
          map input lines (kX string values) to specified output values (vX string values)
          if a default is specified it will be returned for not matching inout lines
          otherwise unmatched input line will result in error
    </pre>

    - :cc rpn
    <pre>
        :cc rpn &lt;expr1> &lt;expr2...>
         treat input as update data (list of Doubles) and compute RPN expressions from them
         input ines are mapped to $dsX values in the expression where X is the zero-based
         index in the list. Output one result (Double) per RPN expression provided
    </pre>

    - :cc rx.. - in all cases &lt;regex> can be quoted using single quotes, double quotes, '|' or '/'.
    
        - :cc rxm
    <pre>
        :cc rxm [-d|--default 'value'] &lt;regex>
            returns entire input (all lines) if matching. if a default value is
            provided via the -d|--default flag value it will be outputted. Result
            will be error otherwise.
    </pre>
 
        - :cc rxml
    <pre>
        :cc rxml [-d|--default 'value'] &lt;regex>
            returns individual matching lines. error if no default value is provided
            and no matching lines (like grep). If a default value is provided (via
            the -d or --default option value) - it will be returned with success response.
    </pre>

        - :cc rxe
    <pre>
        :cc rxe &lt;regex_with_match_groups> &lt;mgrpoupIdx1> &lt;mgrpoupIdx2>
            apply the regex on the entire input (as a string, possibly with new lines) and print
            each match group on a separate line
    </pre>

        - :cc rxel 
    <pre>
        :cc rxel &lt;regex_with_match_groups> &lt;mgrpoupIdx1> &lt;mgrpoupIdx2>
            apply the regex on each input line separately and print each line's match groups on one line
            separated by space
        e.g. :cc rxel |.*(\\d+).*| 1
    </pre>

        - :cc rx_repl
    <pre>
        :cc rx_repl &lt;regex> [replacement]
            for each line in input replace all occurrences of regex with replacements (empty if not specified) str
    </pre>

    - :cc snmpp
    <pre>
        :cc snmpp parse [parse opts]
            -l|--long-keys - keep the full SNMP OID key value (normally the part until the first :: is stripped)

        :cc snmpp get [get opts] &lt;key1> &lt;key2>
            -d|--default &lt;val> - return the supplied default value if a keyX is not found
            missing key with no default value provided will result in error

        :cc snmpp pget [get opts] &lt;key1> &lt;key2>
            parse and get in one shot, no parse options supported
    </pre>

- **influxdb** - influxdb config is specified using the following
    globals (part of the SMG config), with defaults:
    <pre>
    - $influxdb_write_host_port: "localhost:8086"
    - $influxdb_write_url_path: "/api/v2/write?bucket=smg_db&precision=s"
    - $influxdb_write_url_proto: "http"
    - $influxdb_write_batch_size: 1000
    - $influxdb_write_timeout_ms: 30000
    - $influxdb_strip_group_index: true
    </pre>


- **jmx** - exposes the following commands to be used in configs
    - :jmx con host:port
    - :jmx get host:port jmxObj jmxAttr1 jmxAttr2..
    <pre>
        :jmx get host:port java.lang:type=Memory HeapMemoryUsage:used HeapMemoryUsage:committed
    </pre>

- **kube** - TODO

- **mon** - exposes the following alert definitions
    - alert-p-mon-anom: "change_thresh:short_period:long_period"
        - e.g. alert-p-mon-anom: "1.5:30m:30h"
    - alert-p-mon-ex: "period:warn_op-warn_thresh:crit_op-crit_thresh:active_time"
        - e.g. alert-p-mon-ex: "24h:lt-0.7:lt-0.5:18_00-20_00*mon"
    - alert-p-mon-pop: "long_per-short_per:op:warn_thresh:optional_crit_thresh"
        - e.g. alert-p-mon-pop: "24h-5m:lt:0.7" - check the short_per (5m) average
        value against the same value long_per (24h) ago, and compare the difference 
        (proportion) using the provided op (lt, can be lt, lte, gt, gte) against
        the configured warning (0.7) and optional critical thresholds  

- **scrape** - TODO

<a name="running">

## Running and troubleshooting

<a name="pre-reqs" />

### Pre-requisites

SMG needs the following software to run.

- SMG needs **bash** and **gnu timeout** to work out of the box. Bash is
available on pretty much any unix installation and gnu timeout may
have to be installed (on e.g. Mac). It should be possible to run under
Windows using cygwin but never tried.

> Note: The "bash" and "timeout" commands are actually
configurable in application.conf and can be replaced with other
commands with similar functionality. E.g. in theory it should be
possible to replace bash with cmd.exe.

- **rrdtool** - [rrdtool](http://oss.oetiker.ch/rrdtool/index.en.html)
is available as a package on most linux distributions. It comes with
**rrdcached** - a "caching" daemon which can be used for more
efficient updates (rrdtool will not write directly to files but will
send updates to rrdcached for batch updates). Although using rrdcached
is optional (check the [$rrd\_socket global config option](#rrd_socket))
it is highly recommended if you plan to do tens of thousands of
updates every minute.

- **Java 8+** - SMG is written in Scala and needs a JVM (Java 8+) to
run. One must set the **JAVA_HOME** environment variable pointed to that
before launching SMG.

- **httpd** (optional) - SMG comes with its own embedded http server
however since images are served as files from the filesystem it is
more efficient to use a native http server to serve these (e.g. Apache
or Nginx). For this to work one would setup the http server as a
reverse proxy to the SMG instance but bypassing SMG when serving
images. Here is an example apache conf file to enable httpd listening
on port 9080 (in this case SMG is told to output its images in the
/var/www/html/smgimages dir via the [$img\_dir config
option](#img_dir)):

<pre>
    Listen 9080
    &lt;VirtualHost *:9080>
        ProxyPass  /assets/smg !
        ProxyPass        /  http://localhost:9000/
        ProxyPassReverse /  http://localhost:9000/
        Alias /assets/smg /var/www/html/smgimages
    &lt;/VirtualHost>
</pre>

<a name="install-tgz" />

### Installation from .tgz bundle

SMG comes as a pre-built tgz archive named like _smg-$VERSION.tgz_
(e.g. smg-0.3.tgz). You can unpack that anywhere which will be
the SMG installation dir. Here are example commands to unpack SMG in
/opt/smg:

<pre>
# cd /opt
# wget sfw:8080/sfw/smg-0.3.tgz
# tar -xf smg-0.3.tgz
# mv smg-0.3 smg
</pre>

SMG comes with start and stop and scripts which in the above example
would be available as:

- **/opt/smg/start-smg.sh**  - use this to start SMG as a daemon
process. It requires JAVA_HOME to be pointed to a Java 8 installation.

- **/opt/smg/stop-smg.sh**  - use this to gracefully stop the running
as a daemon SMG process.

Before you start SMG you need to create a basic
**/etc/smg/config.yml** file and likely define at least a few RRD
objects. One can use the smgconf/config.yml file inside the
installation dir as example and should check the [configuration
reference](#config) for more details. Note that SMG will happily
start with empty objects list (not that this is much useful
beyond the ability to access this documentation when setting up).

Once you start SMG you can access it by pointing your
browser at port 9000 (this is the default listen port). If using the
httpd.conf example above, that would be port 9080.

Of course one would likely to define many objects before using SMG for
real. Check the [configuration reference](#config) for more details.
We at Smule use chef to manage our servers and also to genereate most
of our SMG configuration (based on hosts/services templates).
Describing this in detail is beyond the scope of this documentation -
use your imagination.

One important note is that whenever you update the yaml config file
you need to tell the running SMG to reload its cofniguration (without
actually restarting SMG). This is done via a http POST request like
this:

<pre>
    curl -X POST http://localhost:9000/reload
</pre>

Or use the bundled with SMG bash wrapper around that command available
as **smgscripts/reload-conf.sh** (relative to the install dir)

In addition, SMG is highly tunable via its Play framework's
application.conf. Check the [Running and troubleshooting](#running)
section for more details.


<a name="install-src" />

#### Installation from sources

- This is mostly meant for development purposes - check [the
developer documentation](../dev/index.md#dev-setup) for setup
instructions.



### Reverse proxy

- see the k8s/ deployment with nginx in front of the Play app
- example apache Reverse proxy conf

<pre>
        Listen 9080

        &lt;VirtualHost *:9080>
            # optional config to intercept /proxy/ requests to the respective remotes
            # and not have the SMG JVM do the proxying
            ProxyPass        /proxy/some-dc/  http://smg1.some-dc.myorg.com:9080/
            ProxyPassReverse /proxy/some-dc/  http://smg1.some-dc.myorg.com:9080/
            ProxyPass        /proxy/other-dc/ http://smg2.other-dc.myorg.com:9080/
            ProxyPassReverse /proxy/other-dc/ http://smg2.other-dc.myorg.com:9080/

            # do not proxy static files/images but serve them directly
            # from the configured SMG $img_dir
            ProxyPass  /assets/smg !
            Alias /assets/smg /opt/smg/public/smg

            # the actual proxy to SMG
            ProxyPass        /  http://localhost:9000/
            ProxyPassReverse /  http://localhost:9000/
        &lt;/VirtualHost>
</pre>


- application.conf

- logs


## [Developer documentation](dev/index.md)

