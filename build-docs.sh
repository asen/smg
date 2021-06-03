#!/usr/bin/env bash

if which pandoc >/dev/null 2>&1 && ! ( pandoc -v | grep "pandoc [0-1]\." >/dev/null ) ; then
  echo "build-docs: Using pandoc (version 2.x or above)"
  MARKDOWN="pandoc -f markdown"
else
  echo "build-docs: Using smgscripts/Markdown.pl (pandoc not available or version below 2)"
  MARKDOWN="smgscripts/Markdown.pl"
fi

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

