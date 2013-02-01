#!/usr/bin/env bash

# This file contains environment variables required to run Spark. Copy it as
# spark-env.sh and edit that to configure Spark for your site. At a minimum,
# the following two variables should be set:
# - MESOS_NATIVE_LIBRARY, to point to your Mesos native library (libmesos.so)
# - SCALA_HOME, to point to your Scala installation

MESOS_NATIVE_LIBRARY=/usr/local/lib/libmesos.so
if [ -z "$SCALA_HOME" ] ; then
  SCALA_HOME=/usr/local/scala-2.9.1-1
fi
