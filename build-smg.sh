#!/usr/bin/env bash
set -e

CLEAN=false
if [ "$1" == "--clean" ] ; then
    CLEAN=true
    shift
fi

NOPKG=false
if [ "$1" == "--no-pkg" ] ; then
    NOPKG=true
fi

VERSION=${VERSION:-1.4}

echo "*** Building docs"

./build-docs.sh

echo "*** Downloading deps"

if [ ! -f public/plugins/jsgraph/pl/plotly-1.18.0.min.js ] ; then
  echo "plotly is not there - downloading"
  wget --no-check-certificate -O public/plugins/jsgraph/pl/plotly-1.18.0.min.js https://cdn.plot.ly/plotly-1.18.0.min.js
else
  echo "plotly is already there"
fi

echo "*** Building version $VERSION"

BNFILE=conf/build-number.conf
if [ ! -f $BNFILE ] ; then
  echo "initialzing new build-number.conf using build-number.conf.init"
  cp $BNFILE.init $BNFILE
fi
# update version
sed "s/smg.version=.*$/smg.version=$VERSION/" $BNFILE > $BNFILE.tmp
mv -f $BNFILE.tmp $BNFILE
# update build number
BNUM=`grep smg.build= $BNFILE | cut -d = -f 2`
let "NBNUM=$BNUM+1"
sed "s/smg.build=$BNUM/smg.build=$NBNUM/" $BNFILE > $BNFILE.tmp
mv -f $BNFILE.tmp $BNFILE
cat $BNFILE


rm -rf public/smg/*.png

if [ "$CLEAN" == "true" ] ; then
  sbt clean
fi

sbt stage

rm -rf target/universal/stage/{smgconf,smgscripts,docker,k8s}

cp -r Dockerfile smgconf smgscripts docker k8s target/universal/stage/

mkdir -p target/universal/stage/{smgrrd/jmx,logs,public/smg,run}

rm -f target/universal/stage/{start-smg.sh,stop-smg.sh,systemd-smg.sh,systemd-template.service}
cp start-smg.sh stop-smg.sh systemd-smg.sh systemd-template.service target/universal/stage/

echo "*** Staging done. Output in target/universal/stage. Preparing versioned dir"

rm -rf target/universal/smg-$VERSION
mkdir target/universal/smg-$VERSION

cp -pr target/universal/stage/* target/universal/smg-$VERSION/

if [ "$NOPKG" == "true" ] ; then
  echo "*** Staging versioned dir done. Output in target/universal/smg-$VERSION"
else
  echo "*** Custom Packaging"
  cd target/universal
  tar -czf smg-$VERSION.tgz smg-$VERSION
  cd ../..

  echo "*** Done. Output in target/universal/smg-$VERSION.tgz"
fi
