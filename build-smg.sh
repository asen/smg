#!/usr/bin/env bash

set -e

#./activator universal:packageZipTarball

VERSION=0.5

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

if [ ! -f conf/build-number.conf ] ; then
  echo "initialzing new build-number.conf using build-number.conf.init"
  cp conf/build-number.conf.init conf/build-number.conf
fi
# update version
sed "s/smg.version=.*$/smg.version=$VERSION/" conf/build-number.conf > conf/build-number.conf.tmp
mv -f conf/build-number.conf.tmp conf/build-number.conf
# update build number
BNUM=`grep smg.build= conf/build-number.conf | cut -d = -f 2`
let "NBNUM=$BNUM+1"
sed "s/smg.build=$BNUM/smg.build=$NBNUM/" conf/build-number.conf > conf/build-number.conf.tmp
mv -f conf/build-number.conf.tmp conf/build-number.conf

cat conf/build-number.conf

rm -rf public/smg/*.png

./activator clean compile stage

rm -rf target/universal/stage/{smgconf,smgscripts}

cp -r smgconf smgscripts target/universal/stage/

mkdir -p target/universal/stage/{smgrrd/jmx,logs,public/smg,run}

rm -f target/universal/stage/{start-smg.sh,stop-smg.sh}
cp start-smg.sh stop-smg.sh target/universal/stage/

echo "*** Custom Packaging"

rm -rf target/universal/smg-$VERSION
mkdir target/universal/smg-$VERSION

cp -pr target/universal/stage/* target/universal/smg-$VERSION/

cd target/universal
tar -czf smg-$VERSION.tgz smg-$VERSION
cd ../..

echo "*** Done. Output in target/universal/smg-$VERSION.tgz"

