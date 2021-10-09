#!/bin/bash

if which pandoc >/dev/null 2>&1 && ! ( pandoc -v | grep "pandoc [0-1]\." >/dev/null ) ; then
  echo "build-html.sh: Using pandoc (version 2.x or above)"
else
  echo "ERROR: build-html.sh: pandoc version 2.x or above is required to build the HTML docs"
  exit 1
fi

cd `dirname $0`

PANDOC_CMD="pandoc -s -f markdown --template=templates/default.html"

$PANDOC_CMD --toc --toc-depth=4 \
    --metadata toc-title="Table of Contents" \
    --metadata index-url=index.html \
    --metadata title='The History and Evolution of a Monitoring System' \
    History_and_Evolution.md > History_and_Evolution.html

$PANDOC_CMD --toc --toc-depth=4 \
    --metadata toc-title="Table of Contents" \
    --metadata index-url=index.html \
    --metadata title='Smule Grapher (SMG) - Concepts Overview' \
    smg.md > smg.html

$PANDOC_CMD --toc --toc-depth=4 \
    --metadata toc-title="Table of Contents" \
    --metadata index-url=index.html \
    --metadata title='Smule Grapher (SMG) - Configuration Reference' \
    smg-config.md > smg-config.html

$PANDOC_CMD --toc --toc-depth=4 \
    --metadata title='Smule Grapher (SMG) - docs index' \
    index.md > index.html

SED=`which gsed 2>/dev/null || which sed`

mkdir -p howto/html

for ht in `ls -1 howto/ | grep -v html` ; do
    ht_html=`echo $ht | $SED 's/\.md$/.html/g'`
    ttl=`echo $ht | $SED 's/\.md$//g' | $SED 's/_/ /g'`
    index_url=index.html
    if [ "$ht_html" == "index.html" ] ; then
        index_url=../index.html
    fi
    $PANDOC_CMD --metadata title-prefix="SMG Howto" --metadata pagetitle="$ttl" \
        --metadata index-url=$index_url \
        howto/$ht > howto/html/$ht_html
done

echo "build-html.sh: Done"
