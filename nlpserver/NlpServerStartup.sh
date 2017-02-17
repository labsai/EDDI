#!/bin/sh
java -classpath '.:lib/*' -DEDDI_ENV=$1 -DEDDI_AUTH=$2 -DEDDI_DB=$3 ai.labs.nlp.NlpServer
#java $JAVA_OPTS -classpath '.:lib/*' -DEDDI_ENV=$1 -DEDDI_AUTH=$2 -DEDDI_DB=$3 ai.labs.nlp.NlpServer
