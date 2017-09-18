# Admin documentation

[Back to main index page](../index.md) | [Developer documentation](../dev/index.md)

## Table of Contents
1. [Install](#install)
    1. [Pre requisites](#pre-reqs)
    1. [Installation from .tgz bundle](#install-tgz)
        1. [Installation from sources](#install-src)
1. [Configuration](#config)
    1. [Globals](#globals)
    1. [RRD objects](#rrd-objects)
    1. [Aggregate RRD objects](#rrd-agg-objects)
    1. [View objects](#view-objects)
    1. [Indexes](#indexes)
    1. [Hidden Indexes](#hindexes)
    1. [Monitoring configuration](#monitoring)
1. [Running and troubleshooting](#running)
    1. Frontend http server
    1. Tweaking SMG for performance

<a name="install" />

## Install

SMG can be installed for a pre-built .tgz or run directly from sources.

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

- **Java 8** - SMG is written in Scala and needs a JVM (Java 8+) to
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

<a name="globals" />

### Globals

Global for SMG variables are defined as a name -> value pairs where the
name is prefixed with '$' sign. Here is the list of supported global
variables, together with their default values (all of these are 
optional):

- **$dash-default-cols**: _6_ - How many "columns" of graphs to display on
the dashboard by default. SMG will insert a new line between graphs at that
"column".

- **$dash-default-rows**: _10_  - How many "rows" of graphs to display on 
the dashboard by default. This together with the number of "columns" 
determines the "page size" - how many graphs will be displayed on each
graphs page.

- **$rrd\_dir**: _"smgrrd"_ - directory where to store your rrd files. 
You probably want to change this on any production installation
(for more demanding setups, may want to put that dir on an SSD drive).
That dir must be writabe by the SMG user. This must be defined before
object definitions in the config (and one can specify it multiple
times, changing the location for subequently defined objects)

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

- **$rrd\_graph\_width**: _607_ - the graph width passed to rrdtool when 
graphing

- **$rrd\_graph\_height**: _152_ - the graph height passed to rrdtool 
when graphing

- **$rrd\_graph\_font**: _"DEFAULT:0:monospace"_ - the graph font string
passed to rrdtool when graphing

- **$rrd\_tool**: _rrdtool_ - the rrdtool command to use. Can specify
full path if the version of rrdtool you want to use is not on 
the default PATH.

<a name="rrd_socket" />

- **$rrd\_socket**: - _(default is None)_. Otherwise one can specify
a string value like /path/to/socket.file. If specified that path will 
be passed to the rrdtool update command with the 
--daemon unix:/path/to/socket.file option. This in turn is
intended for use with rrdcached (a rrdtool daemon used for doing
updates more efficiently). rrdcached is highly recommended on more
demanding setups.

- **$monlog\_dir**: _"monlog"_ - the directory where the monitoring
system saves logs with events.

- **$monstate\_dir**: _"monstate"_ - the directory where the monitoring
system saves its memory state on shut down (and every hour).

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

<a name="pre_fetch" />

- **$pre\_fetch**: pre\_fetch is special and it is not a simple name ->
value pair. A $pre\_fetch defines an unique **id** and a **command** to 
execute, together with an optional **timeout** for the command 
(30 seconds by default) and an optional "parent" pre\_fetch id. In addition
pre\_fetch can have a **child\_conc** property (default 1 if not specified)
which determines how many threads can execute this pre-fetch child pre-fetches
(but not object commands which are always parallelized). Pre fetch also 
supports **notify-unkn** - to override alert recipients for failure (check 
[monitoring config](#monitoring) for details). Here are two example pre_fetch
definitions, one referencing the other as a parent:

<blockquote>
<pre>

    - $pre_fetch:
      id: host.host1.up
      command: "ping -c 1 host1 >/dev/null"
      notify-unkn: mail-asen, notif-pd
      child_conc: 2
      timeout: 5
          
    - $pre_fetch:
      id: host.host1.snmp.vals
      command: "snmp_get.sh o1 laLoad.1 laLoad.2 ssCpuRawUser.0 ssCpuRawNice.0 ..."
      pre_fetch: host.host1.up
      timeout: 30

</pre>
</blockquote>

> As explained in the [concepts overview](../index.md#pre_fetch) SMG
RRD objects can specify a pre\_fetch command to execute before their
own command gets executed (for the current interval run). That way 
multiple objects can be updated from given source (e.g. host/service) 
while hitting it only once per interval. Pre\_fetch itself can have 
another pre\_fetch defined as a parent and one can form command trees 
to be run top-to-bottom (stopping on failure).

> Note that the "run tree" defined in this way must not have cycles 
which can be created in theory by circularly pointing pre\_fetch parents 
to each other (possibly via other ones). Currently SMG will reject 
the config update if circular parent depenencies in the run-tree are
detected (detection is simply having a hard limit of max 10 parent
levels when constructing the run trees).

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
</pre>
</blockquote>

> If the optional **slave_id** parameter is provided it indicates that 
this instance is a "slave" in the context of that remote. Its value must
be the id under this instance is configured on the "master". A slave
instance will not load and display the relevant remote instance config 
and graphs but will only notify it on its own config changes.

> One can run a setup where the "main" instance (can be two of them,
for redundancy) has multiple remotes configured where the remote 
instances only have  the "main" one as configured (for them) remote
(with slave_id set). With such setup one only needs a  single "beefy" 
(more mem) "main" instance which will hold all available across the 
remotes objects and the other ones will only keep theirs.

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

> The actual command gets executed with the following parameters 
appended to the command string:

> - $SEVERITY - one of  RECOVERY, ACKNOWLEDGEMENT, ANOMALY, 
  WARNING, UNKNOWN, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED
> - $ALERT_KEY - the affected object/var, pre-fetch or global issue 
    identification string.
> - $SUBJ - the "subject" for the message to be sent
> - $BODY - the "body" of the message to be sent

> Example command that could be executed:

> smgscripts/notif-mail.sh 'asen@smule.com somebodyelse@smule.com' WARNING host.xxx.sysload:1 'WARNING sl5min > 20' 'A bit more detailed warning info here'

- **$notify-global**: a comma separated list of list of $notify-command 
ids, to be executed on any global SMG errors (usually - overlaps)

- **$notify-crit**: a comma separated list of list of $notify-command 
ids, to be executed on any (global or object-specific) critical errors

- **$notify-unkn**: a comma separated list of list of $notify-command 
ids, to be executed on any (global or object-specific) "unknown" (i.e. 
fetch command failure) errors

- **$notify-warn**: a comma separated list of list of $notify-command 
ids, to be executed on any (global or object-specific) warning errors

- **$notify-anom**: a comma separated list of list of $notify-command 
ids, to be executed on any anomaly (spike/drop) errors. Be warned
that this can get noisy on large setups.

- **$notify-baseurl**: Base url to be used in alert notifications links, 
default is http://localhost:9000 (so you probaly want that set if you 
intend to use alert notifications). This can also be pointed to a 
different ("master") SMG instance URL where the current one is 
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

<a name="rrd-objects" />

### RRD objects

RRD objects usually represent the majority of the SMG config. These 
define a rrd file to be updated, its structure, the interval on
which we want to update it and the command to use to retrieve the 
values to update. A rrd object is represented by a yaml structure 
which could look like this:

<pre>
- host.localhost.sysload:                               # rrd object id
  command: "smgscripts/mac_localhost_sysload.sh"        # mandatory command outputting values
  timeout: 30                                           # optional - fetch command timeout, default 30
  title: "Localhost sysload (1/5/15 min)"               # optional title - object id will be used if no title is present
  interval: 60                                          # optional - default is 60
  dataDelay: 0                                          # optional - default 0
  rrd_type: GAUGE                                       # optional - default is GAUGE. Can be COUNTER etc (check rrdtool docs)
  rrd_init_source: "/path/to/existing/file.rrd"         # optional - if defined SMG will pass --source <val> to rrdtool create
  stack: false                                          # optional - stack graph lines if true, default - false
  pre_fetch: some_pf_id                                 # optional - specify pre_fetch command id.
  notify-unkn: mail-asen,notif-pd                       # optional - sent command failures to these recipients (see notify- conf below)
  vars:                                                 # mandatory list of all variables to graph
    - label: sl1min                                     # the variable label. 
      min: 0                                            # optional - min accepted rrd value, default 0
      max: 1000                                         # optional - max accepted rrd value, default U (unlimmited)
      mu: "lavg"                                        # optional - measurement units, default - empty string
      lt: "LINE1"                                       # optional - line type, default is LINE1
      alert-warn-gt: 8                                  # optional - monitoring thresholds, details below
    - label: sl5min                                     # another variable def
    - label: sl15min                                    # a 3d variable def
</pre>

Most properties are optional except:

- the **object id**: (the key after the dash defining the start of the 
object, *host.localhost.sysload* in the example) must be unique for the 
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
    one would use the following cdef value: _"$ds,8,*"_. This specific 
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

- **dataDelay** - (default - _0_ seconds) - sometimes services report
their data with certain delay (e.g. a CDN API may report the traffic
used with 15 min delay). Set that value to the number of seconds data is
delayed with and on update SMG will subtract that many seconds from the
fetch/update timestamp.

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
for details. Note that from the per-leve specifiers only notify-unkn
is relevant at this level.

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
- +agg.hosts.sysload:                                   # aggregate rrd object id
  op: AVG                                               # mandatory aggregate function to apply
  ids:                                                  # mandatory list of object ids
    - host.host1.sysload                                # regular rrd object id, currently must be defined before this object in the config
    - host.host2.sysload                                # regular rrd object id, currently must be defined before this object in the config
  interval: 60                                          # optional - default is the interval of the first object listed in the ids list
  title: "Localhost sysload (1/5/15 min)"               # optional title - object id will be used if no title is present
  rrd_type: GAUGE                                       # optional - if not set, the rrd_type of the first object will be used
  rrd_init_source: "/path/to/existing/file.rrd"         # optional - if defined SMG will pass --source <val> to rrdtool create
  stack: false                                          # optional - stack graph lines if true, default - false
  notify-unkn: mail-asen,notif-pd                       # optional - sent command failures to these recipients (see notify- conf below)
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

As mentioned in the [concepts overview](../index.md#view-objects)
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

One should not set both gv and cdef_vars on the same View object. If 
one does that the gv values will be ignored. Note that technically it 
is possible to do what gv does using just cdef_vars except 
that gv provides a simpler way to just select a subset or reorder graph
lines.

View objects support the following properties:

- the **object id**: This is the unique object view id (must not
conflict with other view or rrd objects). Check 
[RRD Objects](#rrd-objects) for more info on allowed and good object 
ids.

- **ref**: mandatory reference (string) to a RRD object id. Note:
Currently there is a limitation that the ref RRD object must be defined 
in the yaml before the View objects it will be referenced in.

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
$ds prefix - _$ds0_, _$ds1_, _$ds2_ etc. In the above example we have
 
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
    - ^hosts.localhost:                # index id
      title: "localhost graphs"  # optional - id (sans the ^ char) will be used if not specified
      cols: 6                    # optional (default - the value of $dash-default-cols) how many coulmns of graph images to display
      rows: 10                   # optional (default - the value of $dash-default-rows) how many rows (max) to display. excess rows are paginated
      px: "host.localhost."      # optional filter: rrd object id prefix to match. Ignored if null (which is the default).
#      sx: ...                   # optional filter: rrd object id suffix to match. Ignored if null (which is the default).
#      rx: ...                   # optional filter: rrd object id regex to match. Ignored if null (which is the default).
#      rxx: ...                  #
#      trx: ...                  #
      parent: some.index.id      # optinal parent index id. Indexes without parent are considered top-level
      children:                  # optional list of child index ids to display under this index
        - iid1
        - iid2
      remote: "*"                # optional - index remote, default is null, use "*" to specify cross-colo index and
                                 # should be left unspecified otherwise (SMG will populate it accordingly)
</pre>

Index objects support the following properties:

- the **index id**: This is the unique index id (must not
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
    otherwise - minutes. Use seconds if you really want graphs covering 
    less than 13 minutes (unlikely).
    - _d_ - for days
    - _h_ - for hours
    - (None, just a number) - assumed to be seconds.
The end point of the graphs can be set to be different than
"now" by using a "period length" (**pl**)
[graph option](#graph-options).

- **agg\_op**: (default - None) - A SMG aggregate operation string (one
of _GROUP_, _STACK_, _SUM_, _SUMN_ or _AVG_ ). If that is specified
SMG will directly apply the respective 
[aggregate operation](../index.md#aggregate-functions) to the
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

- **trx** (Text Regex) - when set, only objects with titles, object ids,
graph labels or measurement units matching the specified regular 
expressions will be matched by the filter. 

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
[here](../index.md#sorting).


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
    alert-spike: ""      # anomaly alert - detecting unusual spikes or drops
                         #   it accepts a string with 3 values separated by ":"
                         #   the default value (when empty string is provided) is
                         #   "1.5:30m:30h" which means 1.5 (relative) change
                         #   in the last 30 minutes period compared to the previous 
                         #   30h period.

Check [here](../index.md#anomaly) for more details on how 
alert-spike/anomaly detection works. 

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
    notify-unkn: notify-cmd-id-1,notify-cmd-id-2,...
    notify-warn: notify-cmd-id-1,notify-cmd-id-2,...
    notify-anom: notify-cmd-id-1,notify-cmd-id-2,...
    notify-disable: true
    notify-backoff: 6h
    notify-strikes: 3

> In order to disable fetch error notifications for given object one must
set "_notify-disable: true_" at the object level. Pre-fetch commands support
notify-disable too.

> When multiple conflicting notify-strikes values apply, SMG will 
use the minimal from these. For fetch errors (applicable to object and 
pre-fecth command failures) this means the minimal value (but never 
less than 1) applicable to any of the "children" graph vars (ones 
depending on the failed command).

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
     alert-spike: ""
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
      alert-spike: ""
    - label: 1
      alert-spike: ""
    - label: 2
      alert-spike: ""
    - label: 3
      alert-spike: ""
    - label: 4
      alert-spike: ""
    - label: 5
      alert-spike: ""
    - label: 6
      alert-spike: ""
    - label: 7
      alert-spike: ""
</pre>

<a name="running">

## Running and troubleshooting

TODO

### Reverse proxy

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
             
            # same for some plugin static data, as needed
            ProxyPass  /spiker_hist !
            Alias /spiker_hist /var/www/html/spiker_hist
                    
            # the actual proxy to SMG
            ProxyPass        /  http://localhost:9000/
            ProxyPassReverse /  http://localhost:9000/       
        &lt;/VirtualHost>
</pre>


- application.conf
    - thread pools and performance
- logs

