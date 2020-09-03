#!/usr/bin/env bash
set -e

cd `dirname $0`

PUSH=false
if [ "$1" == "--push" ] ; then
    PUSH=true
    shift
fi

IMAGE_VERSION=${IMAGE_VERSION:-0.1}
IMAGE_NAME=${IMAGE_NAME:-asen/smg}

./build-smg.sh --no-pkg

docker build \
    -t $IMAGE_NAME:$IMAGE_VERSION \
    -t $IMAGE_NAME:latest \
    .
if [ "$PUSH" == "true" ] ; then
    echo "Pushing to container registry ..."
    docker push $IMAGE_NAME:$IMAGE_VERSION
    docker push $IMAGE_NAME:latest
fi

echo "Done."
