#!/usr/bin/env bash

OUTBASEDIR=${1:-public}
if [ "$OUTBASEDIR" == "" ] ; then
    echo "need an output base dir"
    exit 1
fi
OUTDIR=$OUTBASEDIR/docs
SRCDIR=docs

rm -rf $OUTDIR/*
mkdir -p $OUTDIR
mkdir -p $OUTDIR/howto/html

for fn in `ls -1 $SRCDIR/*.html` ; do
    /bin/cp -vf $fn $OUTDIR/
done

for fn in `ls -1 $SRCDIR/howto/html/*.html` ; do
    /bin/cp -vf $fn $OUTDIR/howto/html/
done
