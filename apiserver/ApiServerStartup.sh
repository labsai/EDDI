#!/bin/sh
java -classpath '.:lib/*' -DEDDI_ENV=$1 -DEDDI_AUTH=$2 -DEDDI_DB=$3 ai.labs.api.ApiServer
#java $JAVA_OPTS -classpath '.:lib/*' -DEDDI_ENV=$1 -DEDDI_AUTH=$2 -DEDDI_DB=$3 ai.labs.api.ApiServer
