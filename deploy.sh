#!/bin/bash

SH=${HOSTS//,/ }
if [ -z "$REV" ] ; then
  REV=$(git rev-parse --short HEAD)
fi
VERSION=0.7.0-kryo
sudo cp -r ../spark /usr/local/spark-$VERSION-$REV
for h in ${SH[*]}; do
  echo $h
  echo $REV
  `scp -r /usr/local/spark-$VERSION-$REV $h:/usr/local/`
  `ssh $h "chown -R dev /usr/local/spark-${VERSION}-${REV}; chgrp -R dev /usr/local/spark-${VERSION}-${REV}"`
done
