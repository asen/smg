#!/bin/bash

if which pandoc >/dev/null 2>&1 && ! ( pandoc -v | grep "pandoc [0-1]\." >/dev/null ) ; then
  echo "build-html.sh: Using pandoc (version 2.x or above)"
else
  echo "ERROR: build-html.sh: pandoc version 2.x or above is required to build the HTML docs"
  exit 1
fi

pandoc --toc -s -f markdown --toc-depth=4 \
    --metadata title='The History and Evolution of a Monitoring System' \
    History_and_Evolution.md > History_and_Evolution.html

pandoc --toc -s -f markdown --toc-depth=4 \
    --metadata title='Smule Grapher (SMG)' \
    index.md > index.html

echo "build-html.sh: Done"
