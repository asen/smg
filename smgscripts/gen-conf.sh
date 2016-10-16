#!/usr/bin/env bash

TARGET="$1"
if [ "$TARGET" == "" ] ; then
    echo "Usage: $0 <target> [<fqdn>] [output=/etc/smg/hosts/\$TARGET.yml] [source=smgconf/templ-linux.yml]"
    exit 1
fi
FQDN=${2:-"$TARGET"}
OUTPUT=${3:-"/etc/smg/linux/$TARGET.yml"}
SOURCE=${4:-"smgconf/templ-linux.yml"}

sed "s/localhost.localdomain/$FQDN/g" $SOURCE > $OUTPUT.tmp
sed "s/localhost/$TARGET/g" $OUTPUT.tmp > $OUTPUT
rm -f $OUTPUT.tmp

