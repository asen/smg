#!/usr/bin/env bash

#./activator universal:packageZipTarball

VERSION=0.3

echo "*** Building docs"

./build-docs.sh

echo "*** Building version $VERSION"

rm -rf public/smg/*.png

./activator clean compile stage

rm -rf target/universal/stage/{smgconf,smgscripts}

cp -r smgconf smgscripts target/universal/stage/

mkdir -p target/universal/stage/{smgrrd,logs,public/smg,run}

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

