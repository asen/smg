#!/usr/bin/env bash

MARKDOWN=smgscripts/Markdown.pl
OUTBASEDIR=${1:-public/}
if [ "$OUTBASEDIR" == "" ] ; then
    echo "need an output base dir"
    exit 1
fi
OUTDIR=$OUTBASEDIR/docs
SRCDIR=docs

rm -rf $OUTDIR/*
mkdir -p $OUTDIR


for fn in `find $SRCDIR -type f -name "*.md" | sed "s/$SRCDIR\///g"` ; do
    echo $fn
    outfn=`basename $fn | sed 's/\.md$/.html/g'`
    dn=$OUTDIR/`dirname $fn`
    mkdir -p $dn
    $MARKDOWN $SRCDIR/$fn | sed 's/index.md/index.html/g' > $dn/$outfn
done

