# Developers documentation

[Back to main index page](../index.md)


<a name="dev-setup">
# Development setup 

<ol>
<li>Install JDK 11+</li>
<li>Install rrdtool and coreutils (for gnu timeout command). E.g. with
homebrew on Mac:
<pre>
    $ brew install rrdtool coreutils
</pre>
    <ul><li>
    (Only applicable on Mac) SMG currently needs to use gnu timeout
     where the default timeout command on Mac is different. There are
     two ways to address this (after installing coreutiles and gtimeout)
    <ol>
    <li>change application.conf:
    <pre>
    # Use smg.timeoutCommand to override the timeout command executable (e.g. gtimeout on mac with homebrew)
    smg.timeoutCommand = "gtimeout"
    </pre></li>
    <li>Link /usr/local/bin/timeout to /usr/local/bin/gtimeout on your 
    Mac (that assumes that /usr/local/bin is before /usr/bin in your 
    $PATH 
    <pre>
    ln -s /usr/local/bin/gtimeout /usr/local/bin/timeout
    </pre></li>
    </ol>
    </li></ul>
</li>
<li>Run:
<pre>
    $ cd smg
    $ JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./run-dev.sh
</pre></li>
</ol>

# This page is a TODO - use the source (Luke)

Just some notes on potential future content below

## Dev environment and build

- scala/play/idea

## Architecture

- rrdtool
- Akka
- remotes
- avoid/minimize synchronization when possible

## Modules orientation

- all singletons

    bind(classOf[ExecutionContexts]).to(classOf[SMGExecutionContexts])
    bind(classOf[SMGConfigService]).to(classOf[SMGConfigServiceImpl])
    bind(classOf[SMGRemotesApi]).to(classOf[SMGRemotes])
    bind(classOf[SMGSearchCache]).to(classOf[SMGSearchCacheImpl])
    bind(classOf[GrapherApi]).to(classOf[SMGrapher])
    bind(classOf[SMGSchedulerApi]).to(classOf[SMGScheduler])
    bind(classOf[SMGMonitorLogApi]).to(classOf[SMGMonitorLog])
    bind(classOf[SMGMonNotifyApi]).to(classOf[SMGMonNotifySvc])
    bind(classOf[SMGMonitorApi]).to(classOf[SMGMonitor])
    bind(classOf[CDashboardApi]).to(classOf[CDashboardSvc])

## Plugins
- ...
