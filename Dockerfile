# This should be used from within the staged build dir after ./build-smg.sh
# see ./build-smg.sh and ./build-docker.sh

FROM centos:8

RUN yum install -y gcc make cairo-devel pango-devel libxml2-devel freetype-devel perl-ExtUtils-MakeMaker \
    java-11-openjdk-devel socat
RUN yum install -y rrdtool

RUN mkdir -p /etc/smg/conf.d
RUN mkdir -p /etc/smg/scrape-private.d
RUN mkdir -p /etc/smg/scrape-targets.d
RUN mkdir -p /etc/smg/kube-clusters.d

# define local data dirs and leave everything else to be configured via /etc/smg/conf.d
RUN echo '# /etc/smg/config.yml generated by Dockerfile' > /etc/smg/config.yml
RUN echo '' >> /etc/smg/config.yml
RUN echo '- $rrd_dir: "/opt/smg/data/rrd"' >> /etc/smg/config.yml
RUN echo '- $monlog_dir: "/opt/smg/data/monlog"' >> /etc/smg/config.yml
RUN echo '- $monstate_dir: "/opt/smg/data/monstate"' >> /etc/smg/config.yml
RUN echo '' >> /etc/smg/config.yml
RUN echo '- $include: "/etc/smg/conf.d/*.{yaml,yml}"' >> /etc/smg/config.yml
RUN echo '- $include: "/etc/smg/scrape-private.d/*.{yaml,yml}"' >> /etc/smg/config.yml
RUN echo '' >> /etc/smg/config.yml

VOLUME [ "/etc/smg/kube-clusters.d"]
VOLUME [ "/etc/smg/scrape-targets.d"]
VOLUME [ "/etc/smg/conf.d" ]
VOLUME [ "/etc/smg/scrape-private.d"]
VOLUME [ "/opt/smg/data" ]

RUN mkdir -p /opt/smg
RUN mkdir -p /opt/smg/inst

# this implies top-level dir as context, better use the staging dir as context itself
# COPY target/universal/stage /opt/smg/inst/smg
COPY . /opt/smg/inst/smg

# A hack to point the jmx plugin to use /opt/smg/data/rrd/jmx for rrd data dir
RUN sed -i 's|smgrrd/jmx|/opt/smg/data/rrd/jmx|' /opt/smg/inst/smg/smgconf/jmx-plugin.yml

EXPOSE 9000
EXPOSE 9001
ENV APP_HOME /opt/smg/inst/smg
CMD ["/opt/smg/inst/smg/start-smg.sh", "--wait"]
