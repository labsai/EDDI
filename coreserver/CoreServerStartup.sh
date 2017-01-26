#!/bin/bash

if [ -z "$1" ]; then
    echo "Usage: <environment> [rmi-server-hostname]"
    exit
fi

if [ "$1" = "production" ]; then

read -d '' JAVA_OPTS <<EOF
        -server -Xms1024m -Xmx1024m
        -XX:+UseConcMarkSweepGC
        -XX:+CMSParallelRemarkEnabled
        -XX:+UseCMSInitiatingOccupancyOnly
        -XX:CMSInitiatingOccupancyFraction=50
        -XX:CMSWaitDuration=300000
        -XX:+CMSScavengeBeforeRemark
        -XX:+ScavengeBeforeFullGC
        -XX:+PrintGCDateStamps -verbose:gc -XX:+PrintGCDetails -Xloggc:/var/log/gc.log
        -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=100M
        -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/eddi/`date`.hprof
        -Djava.rmi.server.hostname=$2
        -Dcom.sun.management.jmxremote.port=9010
        -Dcom.sun.management.jmxremote.authenticate=false
        -Dcom.sun.management.jmxremote.ssl=false
EOF

elif [ "$1" = "development" ]; then
    JAVA_OPTS=""
else
    echo "Invalid environment $1"
    exit
fi
java $JAVA_OPTS -classpath '.:lib/*' -DEDDI_ENV=$1 ai.labs.core.CoreServer