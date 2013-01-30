#!/bin/bash


echo "starting master"
./run spark.deploy.master.Master -i localhost &> master.log &

sleep 5

echo "starting worker 1"
./run spark.deploy.worker.Worker spark://localhost:7077 -c 1 -m 512M -i 127.100.0.1 &> worker_1.log &

echo "starting worker 2"
./run spark.deploy.worker.Worker spark://localhost:7077 -c 1 -m 512M --webui-port 8091 -i 127.100.0.2 &> worker_2.log &

echo "local cluster running"
