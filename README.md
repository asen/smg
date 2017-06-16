# Smule Grapher (SMG)

## What is this

Play [2.4/scala](https://www.playframework.com/) app using external 
scripts to fetch numbers from services and [rrdtool](http://oss.oetiker.ch/rrdtool/) 
to maintain many time series databases and display graphs from them. 
It can also do a lot more than that - like checking the fetched valules 
against pre-configured thresholds and/or anomalies and sending alerts. 
It is also possible to extend SMG via plugins (scala extensions).

Intended to be a simple to maintain but scalable monitoring system for 
people who prefer to generate their configs over clicking on UI to set 
it up.

Check the docs/ dir (or Docs link in a running SMG instance) for more 
information.

Docs on github: https://github.com/asen/smg/blob/master/docs/index.md

Binary releases available here: https://github.com/asen/smg/releases

## Build

```
    $ git clone git@github.com:asen/smg.git
    $ cd smg
    $ git checkout release # recommended, to get on a stable branch
    $ ./build-smg.sh
    ...
    *** Done. Output in target/universal/smg-0.5.tgz
```

## Install and configure

* Install prerequisites (e.g on linux):

    ```
        # yum install rrdtool
    ```

* Unpack tgz

    ```
       # tar -xzf smg-0.5.tgz
    ```
 
* Create /etc/smg/config.yml (e.g. by copying the example config into 
/etc/smg/config.yml and editing as needed):

    ```
        # mkdir /etc/smg && cp smg-0.5/smgconf/config-example.yml /etc/smg/config.yml
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

* Point JAVA_HOME to a Java 8 installation and start SMG using the 
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

## Development setup (Mac)

* Install JDK 8.

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
    $ JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./run-dev.sh
    ```
