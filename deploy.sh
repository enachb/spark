#!/bin/bash

SH=${HOSTS//,/ }
if [ -z "$REV" ] ; then
  REV=$(git rev-parse --short HEAD)
fi
VERSION=0.7.0
sudo cp -r ../spark /usr/local/spark-$VERSION-$REV
for h in ${SH[*]}; do
  echo $h
  echo $REV
  `scp -r /usr/local/spark-$VERSION-$REV $h:/usr/local/`
done
