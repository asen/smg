#!/usr/bin/env bash

OUTBASEDIR=${1:-public}
if [ "$OUTBASEDIR" == "" ] ; then
    echo "need an output base dir"
    exit 1
fi
OUTDIR=$OUTBASEDIR/docs
SRCDIR=docs/html

rm -rf $OUTDIR/*
mkdir -p $OUTDIR

for fn in `ls -1 $SRCDIR/` ; do
    /bin/cp -vf $SRCDIR/$fn $OUTDIR/
done

