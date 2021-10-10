

## Introduction

### What is it

Smule Grapher (SMG) is a Play 2.x/Scala app used to retrieve data from arbitrary monitored services (generally - using external/bash commands) and using [rrdtool](https://oss.oetiker.ch/rrdtool/) to maintain many RRD databases with the retrieved data and then plot and display time-series graphs (and much more) from them.

(IMHO) These days there is a monitoring tools gap introduced by abandoining Nagios and its "checks" in favor of Prometheus and its "metrics". SMG can fill that gap for you an can be used as a replacement of any (or both) of these in a lot of use cases.

Some Features:

* simple config suitable for being generated from a configuration management system, like Chef/Puppet etc. E.g

<pre>
    - some.var:
      command: /path/to/script_outputting_var_value.sh
      vars:
        - label: var1
</pre>

* arbitrary number of vars per object/graph allowed (though defining more than 10 is rarely a good idea)
* single instance/config can specify and work with different update periods - like evey minute (the default), every 5 minutes, every hour etc.
* script\_outputting\_var\_value.sh can be a mrtg-compatible helper script
* Remote instances support. One can have multiple instances spread across different geo locations but use single UI access point for all.
* Built-in advanaced monitoring on any graphed value
* Aggregate functions allowing to see the sum/average/etc from arbitrary number of (compatible) values
* Scala plugins support - it is possible to extend SMG by writing plugins for custom functionality including custom data input sources which do not necessarily fit the model where external command is executed on regular interval to fetch the values for updates. E.g. SMG comes with a JMX plugin which keeps persistent connections to the target Java JMX servers. It is also possible to create custom actions attached to graphs using plugins including e.g. visualizations. SMG comes with javascript-based "Zoom" functionality based on the open source plot.ly JS graphing library.
* Support for OpenMetrics (Prometheus) /metrics end-points scrapping and graphs via the Scrape/Autoconf plugins. In addition it is now possible to monitor back-end services (like redis/mysql etc) natively using the Autoconf plugin - based on templates. This replaces the need to run Prometheus "exporters" and can use native clients to get stats from the monitored service. All this can be used with the Kube plugin and K8s annotations for a complete monitoring solution including alerts and graphs.

See concepts overview below for more details. There is also now a (long) document explaining the [history and evolution of SMG](History_and_Evolution.html) which covers these concepts in more detail and the use cases which they are intended to address.


## Install

SMG can be installed and run from a Docker image, a pre-built .tgz or run directly from sources.

See the [README.md](https://github.com/asen/smg/blob/master/README.md) in the source tree for most up to date install instructions

## UI orientation

SMG's main page (/) displays a list of [configured top-level indexes](#concepts-indexes) (this is where the **Configured indexes** menu item points to). These are links to filter result pages (/dash) with pre-set [filters](#concepts-filters). The later page is where one can also enter custom filters to customize the set of displayed objects (possibly - narrowing down the defined in the index filter). You can get an "empty" (match-all) filter by clicking the **All Graphs** link in the top menu. The **Automatic index** represents a tree structure which SMG will automatically create from all object ids. That looks much better if the object ids follow a hierarchical structure where "levels" are seprated by dots. The **Search** menu item allows searching for both objects and indexes. The **Monitor** menu item displays current issues as detected by the [monitoring system](#concepts-monitoring). Any registered SMG [plugins](#concepts-plugins) can show up as menu items too.

<a name="concepts" />

## Concepts overview

SMG uses the (excellent) [rrdtool](http://oss.oetiker.ch/rrdtool/) tool to store the values it fetches from external services into so called Round Robin Database (RRD) files. Check the tool homepage for more details but in a nutshell RRD files are with fixed (upon creation) size which store series of numbers over different periods using bigger averaging steps the older the data gets. So rrdtool (and in turn - SMG) keeps most recent data (by default - 96h in SMG) into the minimal for SMG run interval (default - every-minute) granularity (which corresponds to maximal resolution) and older data gets averaged over longer period and kept like that (at smaller resolution).

<a name="concepts-rrd-objects" />

### RRD objects

Every graph in SMG corresponds to one or more (check aggregate functions below) RRD objects. These are configured in the yaml config and have a bunch of properties (described in detail in the [config section](#rrd-objects) of these docs).
 
The object id is a string (unique across object ids namespace), should be descriptive and must only contain alpha numeric characters and some symbols like '-', '_' and '.'.

The dot can be considered somewhat special as SMG can build an automatic index tree by considering the dot as a level separator and grouping objects with common prefixes. So a good object id can look like this: **class.hostname.svcname** (Example: *host.a1.sysload*). Note that this is only by convention and it is not enforced by SMG, however having good object ids is critical to be able to use it effectively (see Filters below).

Each rrd object also defines one or more variables (**vars**) and the external **command** is expected to output (at least) as many numbers when invoked, one per line. Getting less lines or non-numbers will cause an error (a NaN will be recorded in the RRD). Getting more lines will not trigger an error and SMG will only use the first N (equal to number of variables) lines. This is intentional and is in order to support mrtg helper scripts directly (these normally output hostname and uptime at the end).

Every RRD object also has an **interval** associated with it. That determines how often the command specified in the config will be executed and its output (numbers) recorded in the RRD file. The default interval if not specified in the config is 60 seconds (or every minute).

Check [RRD Objects configuration](smg-config.html#rrd-objects) for more details on what properties are supported for given RRD object.

In addition to regular RRD objects (with values for update coming from external command output) SMG supports Aggregate Rrd Objects. These don't have their own command to run but instead use values fetched for multiple other regular RRD objects to produce a single aggregate value for every variable defined and then update that in a RRD file. These are distinguished from regular RRD objects by prepending a '+' to the object id in the yaml config.

Check [Aggregate RRD Objects configuration](smg-config.html#rrd-agg-objects) for more details on what properties are supported for given Aggregate RRD object.

<a name="concepts-intervals" />

### Intervals, runs and scheduler

SMG has the concept of regular (every *interval* seconds) *runs* where it will execute the commands specified in the objects configs. All objects having the same interval config value will be processed during the respective interval run (also see the next section on pre\_fetch and run trees). All per-interval runs execute in their own separate thread pool, so that e.g. slow hourly commands do not interfere too much with fast every-minute commands. Aggregate RRD object updates happen after all regular rrd objects have been updated, at the end of the interval run. Thread pools are configurable via the $interval\_def global.

By default SMG will use its internal scheduler to trigger the regular runs. Without getting into too much detail here it will trigger (separate) runs for every discovered unique rrd object (or plugin) interval value, whenever the applicable time comes. If desired one can also use an external scheduler like cron. For this to work one needs to set *smg.useInternalScheduler* in application.conf to *false* and then schedule the smgscripts/run-job.sh script as cron jobs (per interval, passed as param to run-job.sh) as needed.

<a name="concepts-pre_fetch" />

### pre_fetch and run command trees

One important thing when trying to get and update RRDs for tens of thousands of values from remote servers every minute is to reduce the number of remote connections one has to make to the target (monitored) servers. For example in mrtg, nagios (and if not misatken - in cacti too) every check/graph object configured results in a call to a separate external command getting remote data and/or a SNMP get.

Instead (and ideally) we would use a single command to get all the values we want from a given host and then possibly produce many rrd objects and graphs from these. Note that this is how Prometheus works with its /metrcis calls but that only works over http.

This is where SMG's pre\_fetch simplifies things - it is a special config object defining an id and a command to execute. Then RRD objects can have a pre\_fetch attribute specified with a value - the mentioned pre\_fetch id.

This allows the pre\_fetch command to store its ouput data cached somehere locally on the SMG host and then the actual RRD object commands do not need to go to the target server but can use the cached local data. For example we use a pre\_fetch command to get all SNMP values we care about from a given host in a single shot (about sysload, cpu usage, i/o, mem etc). Then we update many RRD objects from the pre-fetched data. Since recently pre\_fetch also supports passing the command stdout directly to the child command stdin without the need to use temp files.
 
In addition pre\_fetch itself can specify another pre\_fetch command to be its parent. That way one can define a hierarchical command tree structure ("run tree") where many child commands depend on a single parent command and a set of parents can define a common parent of their own etc. Example use case for this is to define a top-level _ping -c 1 ip.addr_ pre\_fetch command for given host and then all other pre\_fetches (or actual rrd objects) for that host can have the ping pre\_fetch defined as parent and will not run  at all if e.g. the host is down (not ping-able). Having such setup will also make SMG only send a single "failed" alert if a host is down (vs alerts for each individual command).

An important note is that SMG will execute child pre-fetch commands in sequence (but not rrd object commands which are always parallelized up to the max concurrency level configured for the interval). The intent is to be able to limit the number of concurrent probes hitting some target host to 1 per interval. This behavior can be overriden by setting the **child\_conc** property on the pre-fetch command to more than the default 1 (setting the number of max concurrent threads that can execute child pre-fetches of this pre-fetch).

<a name="concepts-period" />

### Period since and period length

By default SMG will plot graphs for a period starting at some point back in time and ending "now". The starting point is determined by the **Period Since** UI param (default is 24h). Sometimes one may want to use a different end point in the graphs than "now". This can be requested using the **Period Len** UI param, specifying how long period the graphs should cover (starting at the point defined with Period Since). Both parameters and their format are described in detail [here](smg-config.html#period)


<a name="concepts-filters" />

### Filters

SMG maintains the list of all objects available for graphing in memory.

Whenever it has to display some set of (different objects) graphs, the selection happens via a filter over the global list. This is how one selects which graphs to see on the page. This is also why object ids are very important, so that you can easily select classes of objects to display together.

Currently one can filter by object id, object "text representation" (title etc), object labels and [remote instance](#concepts-remotes). Below are the currently supported filter fields as present in the UI. These match the [index filter configuration](smg-config.html#filters) options and if any filter turns out to be useful it can be converted to a named index and configured in the yaml for quick access. Also any filter result can be shared by just sharing its SMG URL - the filter fields are sent as plain URL params to SMG.

**Note:** Using regex filter on object id and title to define groups of objects may look awkward at first sight but is actually quite powerful (and the idea was "borrowed" from [mrtg indexmaker](http://oss.oetiker.ch/mrtg/doc/indexmaker.en.html)). By using good object ids to "describe" the SMG objects admins can save themselves the need to explicitly define too many object (grouping/classification) properties upfront, but instead easily use the filters to define arbitrary groups later when needed.


<a name="flt-remote" />

#### Remote filter

- a drop down to select a specific [remote instances](#concepts-remotes) to search in or to search in all remotes via the special asterisk _\*_ remote id. Note that this filter is not visible unless there is at least one configured remote instance.

<a name="flt-titlerx" />

#### Text Regex filter

- when set, only objects where any of object id, title, command, object labels, var labels or measurement units is matched by the specified regular expressions will be matched by the filter. 

<a name="flt-prefix" />

#### Prefix filter

- when set, only object ids having the specified prefix string (starting with it) will be matched by the filter. Note that this is not a regexp but a direct string comparison (and e.g dots are not special match-all symbols).

<a name="flt-suffix" />

#### Suffix filter 

- when set, only object ids having the specified suffix string (ending with it) will be matched by the filter. Note that similar to prefix filter this is not a regexp but a direct string comparison.

<a name="flt-regex" />

#### Regex filter

- when set, only object ids matching the specified regular expressions will be matched by the filter. 

<a name="flt-rxexclude" />

#### Regex Exclude filter

- when set, only object ids NOT matching the specified regular expressions will be matched by the filter. 

<a name="flt-prx" />

#### Parent Regex filter

- when set, only objects whose parent ids match the specified regular expressions will be matched by the filter. 

<a name="flt-labels" />

#### Labels filter

- when set, only objects whitch match the corresponding object labels expression will be matched by the filter. See the Index Filter section of the configuration reference for the [labels filter syntax](smg-config.html#filters-lbls), 

<a name="flt-index" />

#### Index filter

- Where Index ([described here](smg-config.html#indexes)) itself defines a filter, the index id is a "first class" filter member too. So when visiting an Index url one sees the index filter separately from the user filter and the user filter works only within the already filtered by the index filter list of objects. The UI provides options to remove the index filter or merge it with the user filter resulting in an "index-free" filter.


<a name="concepts-indexes" />

### Indexes

An index is basically a named filter with some additional properties. One can configure indexes in the yaml config and SMG can auto-discover indexes with good object ids structure. Indexes can be structured in a hierarchy where each index can specify a parent index id. The main SMG page currently displays all top-level indexes (ones which don't have a parent) and also the a configurable number of levels after that (using the [$index-tree-levels](#index-tree-levels) global var). Check [the index config section](smg-config.html#indexes) for more details on indexes.

<a name="concepts-cdash" />

### Custom dashboards

The custom dashboard represents a single page which can combine the graphs outputs from multiple indexes. The layout of the page can be defined using sizes in the configuration. In addition it can display monitor states and/or logs plus external items via iframe. Check the [$cdash](smg-config.html#cdash) config options for more details.

<a name="concepts-gopts" />

### Graph options

These generally allow one to set some non-default graph options, when desired. By default SMG will plot the data for the requested period but also plot the previous period of the same length using dotted lines. It will also plot a dashed line at the 95 perecntile value for each plotted variable. Both of these can be disabled using the respective check-box in the filter UI.

<a name="concepts-step" />

#### Step

By default rrdtool will pick the "best" possible step (what period a data point in the graph will correspond to) based on the available in RRAs data and the requested period. Usually that will be the highest available resolution data which would also fit on the graph. E.g. for graphs updated every minute, SMG will display 1 data point per minute (1-min average) on a 24-hours graph. If we want to see the data at 5-minute average values we could set step to 300 (seconds) and get that. 

<a name="concepts-maxy" />

#### MaxY

Sometimes one would get big "spikes" in the data where for a short period the data points values get orders of magnitude higher than normal. When displaying such a graph one usually sees a huge spike and the values around it look like a flat line, close to 0. So in order to see details around such spikes one can set the **MaxY** (maximum Y) value of the graphs. Such a graph will have a gap where the spike was occurring but the surrounding time series data can be seen in a good detail.

<a name="concepts-miny" />

#### MinY

Similar to MaxY, MinY determines the minimum Y value which will be displayed on the graphs.

<a name="concepts-rows-and-cols" />

#### Rows and columns

Whenever SMG displays the graphs resulting from the given filter it uses the **Rows** and **Columns** form parameter values to limit the displayed graphs (it is unlikely that we want 10000 graphs displayed on one page). So one would see at most "Columns" columns of graphs in a "row" (may be less, depending on screen size) with at most "Rows" rows per page.

<a name="concepts-sorting" />

#### Sorting and grouping

SMG supports sorting a displayed page of graphs by the average value of a given object variable. The object variable is specified via the 1-based integer position of the variable within the list of object ones - the *x-sort* dashboard filter form field.

For example if you have page of graphs showing network switch ports (objects with two variables - one for incoming and one for outgoing traffic) and if you want to sort them by their outgoing traffic (that would be the second var in the object's vars list) you would set *x-sort* = 2. The default *x-sort* value (0) implies no sorting.

One important note is that sorting is an expensive operation - it involves a *rrdtool fetch* command in addition to the graph command (*rrdtool graph*) and the former is almost as expensive as the later. Because of this currently SMG will only sort within the currently displayed page of graphs. This is mainly to avoid cases where (possibly by mistake) one can try to sort all graphs (possibly hundreds of thousands) by setting x-sort to 1 on a match-all filter.
 
The current workaround to that limitation is to set a high-enough *rows* parameter so you get all the graphs you want to sort on one page which you can sort after.

A special case of sorting is group by (with value of x-sort=-1, or the GroupBy button). In this case graphs will be grouped for display based on the Group By drop down value.

The different options in the drop down match the Index "group by" (gb) config values and are described in detail in the respective [configuration reference section](smg-config.html#group-by).

<a name="concepts-aggregate-functions" />

### Aggregate functions

SMG supports "aggregating" graphs for objects which have compatible set of time series and grouped based on the Group By drop down value mentioned above. This works using one of the currently available functions (these are available as buttons on the graphs display page):

- **GROUP** - just putting the same type graphs together in a single graph
- **STACK** - stacking the graph lines on top of each other
- **SUM** - summing the same-type lines form the multiple objects together
- **SUMN** - same as SUM except that NaN values are treated as 0. This is mostly useful when you want to see some historical data for a sum of graphs but where some of these were created later. With SUM one would see gaps where one of the component has NaN value for the time point where SUMN will happily conver the NaNs to 0 and still display a line.
- **AVG** - similar to SUM but displaying graphs for the average value of the same-typed lines.
- **MAX** - similar to SUM but displaying graphs for the max value across the same-typed lines.
- **MIN** - similar to SUM but displaying graphs for the min value across the same-typed lines.

By default (and when the filter results in objects from multiple remotes) SMG will do the aggregation "by remote", i.e will produce separate aggregate images for given set of identical var-definition objects - one per remote instance. One can request a **Cross-remote** aggregation by selecting the respective check-box, before clicking on the aggregate function button. This works by downloading the remote rrd files locally and then producing an image from them so it is expected to be somewhat slower.

<a name="concepts-view-objects" />

### View objects and calculated graphs

In addition to the mentioned RRD objects, SMG also supports *View objects*. These are defined on top of RRD objects - every View object must reference a RRD Object. Technically SMG cares only about RRD (update) objects when doing the interval runs for updates but only cares about View objects when filtering for display. 

By default every RRD Object is also a View object so one does not need to explicitly define View objects except in some cases described below. 

View objects are useful to display a subset of an existing RRD object vars (lines) and/or re-order them via the [gv ("graph vars")](smg-config.html#gv) config value.
 
The other use case of View objects is that these support special *cdef variables*. rrdtool supports complex arithmetic expressions (using the available variables) to calculate plotted values. More details [here](http://oss.oetiker.ch/rrdtool/tut/cdeftutorial.en.html).

One example use case for this is varnish stats - varnishstat reports separate "requests" and "cache hit" (simple) counters. Normally one defines these as COUNTERs in rrdtool and gets an "average rate" (number/sec) when plotting/fetching csv. So we can define a special cdef variable in SMG which divides the "cache hit" rate (x 100) by the total "requests" and we effectively get an average "cache hit percentage" for the given period.

Check the [Cdef variables](smg-config.html#cdef_vars) section for more details.

<a name="concepts-remotes" />

### Remotes

SMG was designed with multiple data centers (a.k.a. "*remotes*") in mind from the start (this was actually one of the drivers for us to create SMG).

We run multiple Data Centers (DCs) at Smule. Normally one does not want to run cross-dc monitoring probes (often simply because its too slow). Also one can not expect a single monitoring instance (whether SMG, mrtg or cacti) to be able to keep up with a huge number of every-minute updates, whether across multiple data centers or within the same DC. So having one humongous monitoring instance does not work and normally people using mrtg or cacti would split the graphs across multiple instances to reduce the number of graphs each individual instance needs to keep up with. The draw-back is that one has to go to different host/url to get the relevant graphs and there is no single "namespace" where one can view graphs from multiple instance in the same UI.

This is where SMG *remotes* come into play. Similar to mrtg and cacti we would split the stuff we want graphed into multiple instances. Each of these instances will maintain and update its own subset of the rrd objects. 

However in SMG one can define *remote* instances (host:port) where it is able to get the configured (RRD+View) objects in the remote instance and "merge" these into the local objects list. All remote ids are prefixed with *@&lt;remoteid>.* when merged into the local object ids namespace to avoid name conflicts (the '@' symbol is otherwise forbidden in object ids).

Then [filters](#concepts-filters) can specify filtering within one or more specific remotes or can specify the special '*' (wildcard) remote id to match objects across all remotes.

Internally this works over remote (json-response) http APIs which expose all needed graphing and config/data retrieval operations.

Note that if some remote SMG node dies its graphs/data will become unavailable (i.e. at this point there is no "high availability"), it is mostly a convenience to get all monitoring in a single UI "dashboard". 

<a name="concepts-plugins" />

### Plugins

SMG supports plugins (configured in application.conf) which are just Scala classes implementing a specific interface (trait in Scala terms).

At a high level plugins can do a bunch of things:

- each plugin has an *interval* associated with it. Its run() method will be executed by the scheduler as needed, every *interval* seconds.

- plugins can provide custom RRD objects implementations. For example some data may be available for graphing later - e.g. logs rotated hourly where every hour we want to graph the previous hour (now rotated) data, e.g. per minute. This does not fit in the usual SMG model of run-this-command-every-X-minutes-to-get-data-points and we have plugin(s) which can do this kind of stuff. Also, there may be some cases where you don't even know all the objects you want to graph upfront (so that you could configure them). A custom SMG plugin can dynamically discover objects as they appear.

- provide custom indexes - similar to custom objects, plugin can provide relevant indexes too (normally - grouping the custom plugin objects in some way which makes sense)

- plugin can also provide arbitrary display of some relevant data (e.g. **calc** plugin) and also provide "object actions" to provide display of data specific to an object (e.g. the **jsgraph** plugin providing "Zoom" and "Historam" actions)

- Plugin can implement monitor checks (via valueChecks method) to extend the available by default alert-[warn|crit]-[gt[e]|lt[e]|eq] options. E.g. one can check for value anomalies etc. See the mon plugin for example.

- Plugins can implement custom dashboard items - TODO

- Plugins can implement "internal commands" for various reasons including e.g. keeping persistent JMX connections between runs or more efficient parsing of things like OpenMetrics (Prometheus) and CSV stats, 

#### Currently available plugins

- jsgraph - this plugin provides a JavaScript graphing capabilities for objects exports two actions ("Zoom" and "Histogram"). It works by using the existing API to fetch raw csv data from the rrd object(s) and using the [plot.ly](http://plot.ly/) library to display fancier graphs client-side.

- calc - this plugin provides ability to plot arbitrary graph from SMG object time-series by applying complex arithmetic expressions involving these time-series.

- jmx - one can fetch JMX values using bash command wrappers which might work for longer update intervals and not too many objects but is problematic as establishing the JMX connection can be slow (and thus - expensive). It would be much better to to use persistent connections and this is what the SMG JMX plugins provides. Documentation TBD

- mon - the "mon" (SMGMonCheckPlugin) plugin currently implements two types of value checks - anomaly detection and period-over-period check. Plugin checks are configured using the special alert-p-_pluginId_-_checkId_: "_conf_" config attribute.

- rrdchk - being able to check and fix rrd files for cinsistency with configuration

- scrape - a "Prometheus replacement" plugin which can generate SMG objects/configs from "scrape targets" wich are http end-points exposing metrics in specifc (OpenMetrics) format, usually at /metrics URL. Note that SMG itself exposes such /metrics end-point and the scrape plugin is enabled to process localhost:9000/metrcis by default. TODO - config reference doc. The "Prometheus replacement" part is now deprecated in favor of autoconf which can do the same using the openmetrics template.

- autoconf - this is very similar to the scrape plugin but can support arbitrary protocols via special "templates" - essentially specifying the "type: of service being monitored. That allows monitoring using the native service protocols and nit needing prometheus "exporters". TODO - config reference doc.

- kube - a (work-in-progress) Kubernetes monitoring plugin. It is able to auto-discover K8s nodes, end-points, services etc and metrics from that (in similar way Prometheus does that). The in-cluster services can be annotated for monitoring and these annotations are compatible with Prometheus. It can also use autoconf templates in addition to Prometheus/OpenMetrics end-points.TODO - config reference doc

- influxdb - plugin to forward all data writes to an influxdb end-point (in batches) in format compatible with the Prometheus influxdb adapter.

- cc - (CommonCommands) implements various commands including parsers+getters for CSV output, SNMP (snmpget) output, string transformers as more efficient replacements for using bash/cut/awk etc as external commands.

<a name="concepts-monitoring" />

### Monitoring

SMG main function is to fetch many values from various services and keep RRD databases updated using that information. Also in traditional setups one might run mrtg or cacti (or SMG) for graphing and nagios for 
alerting. This implies that a lot of values are fetched twice - once for nagios and once for graphing. So it makes sense to be able to define alerts based on the wealth of values SMG fetches and avoid the double-hit on the target servers.

The original idea for SMG monitoring was to be implemented as plugins fetching the data from the rrd files and determining whether values are within thresholds. This also allows one to check data at arbitrary (as available in the RRD files) resolution for desired periods. Unfortunately there is one significant issue with that approach - it is too heavy to apply on all objects (based on experience it can almost half the number of objects SMG is capable to update every minute on given hardware). 

This is not acceptable so now (as of v0.2) SMG has built-in monitoring based on a "live" (in memory) data feed consisting of all the values fetched for RRD updates. One can define alerts (acceptable values/ranges) in SMG as part of the object variables configurations or as part of indexes (including "hidden" indexes which sole current purpose is to define alerts).

The [monitoring page](/monitor) page displays all (local or remote) current problems (anything with non-OK state). SMG supports the following states, sorted by severity:

- **OK** - The normal state - a valid value was retrieved and updated in the RRD. 

- **ANOMALY** - Currently there are two types of anomalies, the first is a "counter overflow", e.g. a 32 bit counter passes 2^32 (or was reset) resulting in NaN value and the second one is a "spike" or "drop" - read the notes below for more details on [anomaly detection](#concepts-anomaly).

- **WARNING** - A retrieved value matched against some defined "warning" threshold. 

- **FAILED** - A data retrieval failed - the external command (pre-)fetching data failed. This can happen for various reasons including target host or service down. 

- **CRITICAL** - A fetched value matched against some defined "critical" threshold. 

- **SMGERR** - global SMG error. Usually - overlapping runs (or SMG bug).

Check [monitoring configuration](smg-config.html#monitoring) for more details on how the thresholds are defined in the yaml configuration.

These states come in two flavors - "**HARD**" (at least consecutive 3 non-ok states) and "**SOFT**" (less than 3 consecutive non-ok states). The OK state is always HARD. These concepts are borrowed from nagios.

On shut-down all the in-memory state for monitoring is saved to json files (in the directory specified by the **$monstate\_dir** global config value) and loaded on start-up.

SMG also supports "silencing" alerts which is basically hiding them from the error page and preventing alert notifications. There are two types of silencing:

- **Acknowledge** - this will "silence" a current error until it gets back into a normal state which will automatically clear the  acknowledgement.

- **Silence for** - silence some error for given time period after which the silencing will expire. Silencing does not clear on recovery which is different than acknowledgement and is useful when one wants to prevent alerts upfront when some host or service has planned maintenance and downtime and also for services which are
in "flapping" state - flipping between OK and non-OK states (otherwise acknowlegement is better).

- **Sticky silencing** - this allows one to define regex (and regex exlude) filters which will silence all matching objects but also matching ones defined in the future (until the silencing period expires). This function is available as a check-box in the Monitor - State trees page described below.

By default SMG will not show Soft, Acknowledged or Silenced errors in the Monitor page - this can be toggled for each type using the respective checkboxes.

In addition to current error states SMG keeps track of recent error events on the Monitor - [Event log](/monitor/log) page. Internally these are stored in log files under the directory speciified by the 
**$monlog\_dir** global config value.

One can browse the current SMG state trees (built based on the Run trees mentioned above) in the Monitor - [State trees](/monitor/trees) page. This page allows easy upfront silencing of mutltiple hosts/services when these have maintenance/downtime pending. This is also where the Sticky silencing check-box is available.

All currently silenced states and also currently active Sticky silences can be seen in the Monitor - [Silenced states](/monitor/silenced) page. This page useful to explicitly un-silence objects and remove active Sticky silences before the silencing period has expired.

Ths Monitor - [Run trees](/monitor/runtree) page display the entire internal SMG Run trees (per-interval) structure. Useful to troubleshoot config issues and sanity checking what SMG does on every interval run.

There is also a [heatmaps page](/monitor/heatmap) available where one can see a graphical representation of the overal systems health (work in progress and subject to change).

<a name="concepts-anomaly">

#### Anomaly detection (via the "mon" check plugin)
 
The SMG Mon check plugin anomaly detection works based on a "threshold for difference" (default = 1.5) and two time periods - shorter (default 30 minutes) and longer (default - 30 hours). Will use the default values in the examples below.

First, for any value which has a "spike check" configuration ("alert-p-mon-anom") enabled  SMG will start keeping in-memory stats about the most recent values. The number of stats that will be kept depends on the object interval compared to the configured check periods - e.g. for a graph updated every minute, 30 minutes would map to 30 data points and 30h to - 1800. SMG keeps the short period values (30 in the example) at full granularity. For the long period data instead of keeping all individual 1800 values SMG will group them in overlapping chunks each with the size of the short period or each chunk will cover 30 minutes. How that works is that internally SMG keeps up to half-short period (30 / 2 = 15) values more at full granularity (in addition to the 30 short-term ones) named "previous short term" values. Whenever that list size increases to half the short-period, its values are combined with the first half of the short-period values (for a full short-period sized chunk) and SMG will add an aggregate value to the "long term" stats list before truncating the "previous short term" list to empty (and also throwing away a long term stat if above max size). The actual aggregation consist of calculating the following stats for the chunk:

- average - the average value - sum(values) / count(values)
- [variance](http://www.mathsisfun.com/data/standard-deviation.html) - this is a "population variance"
- 90 percentile value - the max value after throwing away the top 10% values
- count - how many data points were used to calculate the above stats (useful to know when the threshold/periods change).

So in our example SMG would keep between 30 and 45 data points at max granularity and an up to 120 data points for the long period.

The second part is the actual check logic. This is still somewhat work in progress but at a high level the logic is based on chunks stats "similarity". So when checking for alert SMG will calculate the same chunk stats from the short-term stats and the previous short-term stats. Then it will compare the calculated short-term chunk stats with all of the long term period stats. If any of the short-term stats average, varinace or 90%-ile values are within 1.5 (the mentioned "difference" threshold) times difference (more or less) from the compared long term stat then the two stats are "similar" and SMG will rule out the possibility that there is an anomaly (it has "seen" similar values already).

There are some additional checks to see whether it is a spike or drop but overall the approach is to focus on finding patterns proving that the current state is NOT an anomaly.

<a name="running">

## Running and troubleshooting

<a name="docker-mode" />

### Run in a container

* mkdir -p /opt/smg/data /etc/smg/conf.d

* docker run -d --name smg -p 9000:9000 -v /opt/smg/data -v /etc/smg/conf.d/ gcr.io/asen-smg/smulegrapher:latest

* Point your browser to http://$DOCKER_HOST:9000 (the local metrics stats should show up in a minute or two)

* Then add stuff under /etc/smg/conf.d (and/or /opt/smg/data/conf/autoconf.d) and to reload conig use one of:
    * docker exec smg /opt/smg/inst/smg/smgscripts/reload-conf.sh
    * curl -X POST http://$DOCKER_HOST:9000/reload

<a name="k8s-mode" />

### Run in k8s

Check the k8s/ dir for example deployment yamls, including in-cluster monitoring with auto-discovery (similar to Prometheus)

<a name="classic-mode" />

### Classic mode

<a name="pre-reqs" />

#### Pre-requisites

SMG needs the following software to run.

- SMG needs **bash** and **gnu timeout** to work out of the box. Bash is available on pretty much any unix installation and gnu timeout may have to be installed (on e.g. Mac). It should be possible to run under Windows using cygwin but never tried.

> Note: The "bash" and "timeout" commands are actually configurable in application.conf and can be replaced with other commands with similar functionality. E.g. in theory it should be possible to replace bash with cmd.exe.

- **rrdtool** - [rrdtool](http://oss.oetiker.ch/rrdtool/index.en.html) is available as a package on most linux distributions. It comes with **rrdcached** - a "caching" daemon which can be used for more efficient updates (rrdtool will not write directly to files but will send updates to rrdcached for batch updates). Although using rrdcached is optional (check the [$rrd\_socket global config option](#rrd_socket)) it is highly recommended if you plan to do tens of thousands of updates every minute.

- **Java 11+** - SMG is written in Scala and needs a JVM (Java 11+) to run. One must set the **JAVA_HOME** environment variable pointed to that before launching SMG.

- **httpd** (optional) - SMG comes with its own embedded http server however since images are served as files from the filesystem it is more efficient to use a native http server to serve these (e.g. Apache or Nginx). For this to work one would setup the http server as a reverse proxy to the SMG instance but bypassing SMG when serving images. Here is an example apache conf file to enable httpd listening on port 9080 (in this case SMG is told to output its images in the /var/www/html/smgimages dir via the [$img\_dir config option](#img_dir)):

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

#### Installation from .tgz bundle

SMG comes as a pre-built tgz archive named like _smg-$VERSION.tgz_ (e.g. smg-0.3.tgz). You can unpack that anywhere which will be the SMG installation dir. Here are example commands to unpack SMG in /opt/smg:

<pre>
# cd /opt
# wget https://github.com/asen/smg/releases/download/v1.3/smg-1.3.tgz
# tar -xf smg-*.tgz
# mv smg-* smg # (or ln -s smg-* smg)
</pre>

SMG comes with start and stop and scripts which in the above example would be available as:

- **/opt/smg/start-smg.sh**  - use this to start SMG as a daemon process. It requires JAVA_HOME to be pointed to a supported Java installation.

- **/opt/smg/stop-smg.sh**  - use this to gracefully stop the running as a daemon SMG process.

Before you start SMG you need to create a basic **/etc/smg/config.yml** file and likely define at least a few RRD objects. One can use the smgconf/config.yml file inside the installation dir as example and should check the [configuration reference](smg-config.html#config) for more details. Note that SMG will happily start with empty objects list (not that this is much useful beyond the ability to access this documentation when setting up).

Once you start SMG you can access it by pointing your browser at port 9000 (this is the default listen port). If using the httpd.conf example above, that would be port 9080.

Of course one would likely to define many objects before using SMG for real. Check the [configuration reference](#config) for more details. We at Smule use chef to manage our servers and also to genereate most of our SMG configuration (based on hosts/services templates). Describing this in detail is beyond the scope of this documentation - use your imagination.

One important note is that whenever you update the yaml config file you need to tell the running SMG to reload its cofniguration (without actually restarting SMG). This is done via a http POST request like this:

<pre>
    curl -X POST http://localhost:9000/reload
</pre>

Or use the bundled with SMG bash wrapper around that command available as **smgscripts/reload-conf.sh** (relative to the install dir)

In addition, SMG is highly tunable via its Play framework's application.conf. Check the [Running and troubleshooting](#running) section for more details.


<a name="install-src" />

#### Installation from sources

- This is mostly meant for development purposes.

#### Reverse proxy

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


<a name="dev-setup">

## Developer documentation

Use the source ...

### Development setup 

* Install JDK 11+<
* Install rrdtool and coreutils (for gnu timeout command). E.g. with homebrew on Mac:

    $ brew install rrdtool coreutils

* (Only applicable on Mac) SMG currently needs to use gnu timeout where the default timeout command on Mac is different. There are two ways to address this (after installing coreutiles and gtimeout)
    - change application.conf:

    <pre>
    # Use smg.timeoutCommand to override the timeout command executable (e.g. gtimeout on mac with homebrew)
    smg.timeoutCommand = "gtimeout"
    </pre>

    * Link /usr/local/bin/timeout to /usr/local/bin/gtimeout on your Mac (that assumes that /usr/local/bin is before /usr/bin in your $PATH 

    <pre>
    ln -s /usr/local/bin/gtimeout /usr/local/bin/timeout
    </pre><

* Run:

<pre>
    $ cd smg
    $ JAVA_HOME=$(/usr/libexec/java_home -v 11) ./run-dev.sh
</pre>



