# This should be used from within the staged build dir after ./build-smg.sh
# see ./build-smg.sh and ./build-docker.sh

# FROM centos:8
FROM docker.io/rockylinux/rockylinux:8

RUN yum install -y gcc make cairo-devel pango-devel libxml2-devel freetype-devel perl-ExtUtils-MakeMaker \
    java-11-openjdk-devel socat
RUN yum install -y rrdtool

# needed to be able to send e-mail alerts
RUN yum install -y mailx

# optional tools, helpful to have in the container (none of these is required by SMG)
RUN yum install -y diffutils nmap-ncat
# install some clients for common services. note that we don't need the services, just their clients
# also some scripting languages and tools to make writing fetch commands easy
RUN yum install -y mysql redis net-snmp-utils ruby python36 jq perl-XML-XPath bind-utils

RUN yum -y install python3-pip
RUN pip3 install awscli

#RUN echo '#!/bin/bash' > /usr/bin/tail-log ; \
#    echo "tail -n2000 -f /opt/smg/inst/smg/logs/application.log" >> /usr/bin/tail-log ; \
#    chmod +x /usr/bin/tail-log

RUN yum -y install nginx
RUN yum -y install epel-release
RUN yum -y install inotify-tools

# more clients for various services
RUN curl -L https://archive.apache.org/dist/kafka/2.7.2/kafka_2.13-2.7.2.tgz \
  | tar -xzC /opt \
  && ln -s /opt/kafka_2.13-2.7.2 /opt/kafka

COPY docker/nginx-proxy.conf /etc/nginx/nginx.conf
COPY docker/nginx-proxy-compose.conf /etc/nginx/nginx-compose.conf
COPY docker/run-*.sh /

RUN mkdir -p /etc/smg/conf.d && \
    mkdir -p /opt/smg/data/conf/scrape-private.d  && \
    mkdir -p /opt/smg/data/conf/scrape-targets.d && \
    mkdir -p /opt/smg/data/conf/kube-clusters.d

# define local data dirs and leave everything else to be configured via /etc/smg/conf.d
RUN echo '# /etc/smg/config.yml generated by Dockerfile' > /etc/smg/config.yml && \
    echo '' >> /etc/smg/config.yml && \
    echo '- $rrd_dir: "/opt/smg/data/rrd"' >> /etc/smg/config.yml && \
    echo '- $monlog_dir: "/opt/smg/data/monlog"' >> /etc/smg/config.yml && \
    echo '- $monstate_dir: "/opt/smg/data/monstate"' >> /etc/smg/config.yml && \
    echo '' >> /etc/smg/config.yml && \
    echo '- $include: "/etc/smg/000-rrdcached.yml"' >> /etc/smg/config.yml && \
    echo '- $include: "/etc/smg/conf.d/*.{yaml,yml}"' >> /etc/smg/config.yml && \
    echo '- $include: "/opt/smg/data/conf/scrape-private.d/*.{yaml,yml}"' >> /etc/smg/config.yml && \
    echo '- $include: "/opt/smg/data/conf/autoconf-private.d/*.{yaml,yml}"' >> /etc/smg/config.yml && \
    echo '' >> /etc/smg/config.yml

VOLUME [ "/etc/smg/conf.d" ]
VOLUME [ "/opt/smg/data" ]

RUN mkdir -p /opt/smg && mkdir -p /opt/smg/inst

# this implies top-level dir as context, better use the staging dir as context itself
# COPY target/universal/stage /opt/smg/inst/smg
COPY . /opt/smg/inst/smg

#RUN ln -s /opt/smg/inst/smg/logs /var/log/smg

RUN ln -s /opt/smg/inst/smg/start-smg.sh /start-smg.sh

EXPOSE 9000
EXPOSE 9001
ENV APP_HOME /opt/smg/inst/smg

ENTRYPOINT ["/run-entrypoint.sh"]
CMD ["/start-smg.sh", "--wait"]
