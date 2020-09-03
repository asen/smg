#!/usr/bin/env bash
set -e

cd `dirname $0`

IMAGE_VERSION=${IMAGE_VERSION:-0.1}

./build-smg.sh --no-pkg

docker build \
    -t asen/smg:$IMAGE_VERSION \
    -t asen/smg:latest \
    .

echo "Done."
