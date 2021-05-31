#!/usr/bin/env bash

export VERSION=${VERSION:-1.3}
IMAGE_NAME=${IMAGE_NAME:-gcr.io/asen-smg/smg-$VERSION}

# these dirs below assume you have permissions to write to them
# change the local dirs if thats not the case

LOCAL_CONFD_DIR=/etc/smg/conf.d
LOCAL_DATA_DIR=/opt/smg/data

function my_mkdir {
  dir=$1
  if ! mkdir -p $dir ; then
    echo "Failed to create dir: $dir. Do you have permissions?"
    echo "Consider changing the local $dir dir to something writable by your user"
    exit 1
  fi
}

my_mkdir $LOCAL_CONFD_DIR
my_mkdir $LOCAL_DATA_DIR

# -v host_dir:container_dir
docker run --name smg -p 9000:9000  \
  -v $LOCAL_CONFD_DIR:/etc/smg/conf.d -v $LOCAL_DATA_DIR:/opt/smg/data \
  $IMAGE_NAME:latest

# kill in another window:
#   docker kill smg

docker rm smg
