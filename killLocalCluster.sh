#!/bin/bash

echo "killing master"
kill -9 $(jps -l | grep spark.deploy.master.Master | awk {'print $1'})
sleep 5

echo "remaining spark processes:"
jps -lvm | grep spark
