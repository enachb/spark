#!/bin/bash

if [ -z "$HOSTS" ] ; then
  USER="root"
  CLUSTER="dhd"
  HOSTS="${USER}@${CLUSTER}0"
  for i in {1..11}
  do
    HOSTS="${HOSTS},${USER}@${CLUSTER}${i}"
  done
fi

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
