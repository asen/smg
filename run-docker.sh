#!/usr/bin/env bash

IMAGE_NAME=${IMAGE_NAME:-gcr.io/asen-smg/smulegrapher}
SMG_MEMORY=${SMG_MEMORY:-"512M"}

# these dirs below assume you have permissions to write to them
# change the local dirs if thats not the case

LOCAL_CONFD_DIR=${SMG_CONFD_DIR:-"/etc/smg/conf.d"}
LOCAL_DATA_DIR=${SMG_DATA_DIR:-"/opt/smg/data"}

RUN_OPTS="-it"
if [ "$1" == "-d" ] ; then
  echo "Launcing in detached mode, use docker kill smg to kill"
  RUN_OPTS="-d"
else
  echo "Launcing in interactive mode, use Ctrl+C to kill"
fi

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
cmd="docker run $RUN_OPTS --rm --env SMG_MEMORY=$SMG_MEMORY --name smg -p 9000:9000  \
  -v $LOCAL_CONFD_DIR:/etc/smg/conf.d:z -v $LOCAL_DATA_DIR:/opt/smg/data:z \
  $IMAGE_NAME:latest"

echo "Using cmd:"
echo "$cmd"
exec $cmd
