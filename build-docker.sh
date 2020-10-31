#!/usr/bin/env bash
set -e

cd `dirname $0`

PUSH=false
if [ "$1" == "--push" ] ; then
    PUSH=true
    shift
fi

export VERSION=${VERSION:-1.2}

IMAGE_VERSION=${IMAGE_VERSION:-0.2}
IMAGE_NAME=${IMAGE_NAME:-gcr.io/asen-smg/smg-$VERSION}


echo "Using VERSION=$VERSION IMAGE_NAME=$IMAGE_NAME IMAGE_VERSION=$IMAGE_VERSION"

./build-smg.sh "$@"

echo "Done with SMG build, proceeding with container building"

docker build \
    -t $IMAGE_NAME:$IMAGE_VERSION \
    -t $IMAGE_NAME:latest \
    target/universal/smg-$VERSION

if [ "$PUSH" == "true" ] ; then
    echo "Pushing to container registry ..."
    docker push $IMAGE_NAME:$IMAGE_VERSION
    docker push $IMAGE_NAME:latest
fi

echo "Done."
