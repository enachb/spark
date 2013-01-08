#!/bin/bash

SH=${HOSTS//,/ }
REV=`git rev-parse --short HEAD`
for h in ${SH[*]}; do
  echo $h
  echo $REV
  `scp -r /usr/local/spark-0.6.2-$REV $h:/usr/local/`
done
