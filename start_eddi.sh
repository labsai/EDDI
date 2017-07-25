#!/usr/bin/env bash
env_vars=($JAVA_MONGODB_HOST)
argument_string=""

for item in ${env_vars[*]}
do
    argument_string="${argument_string} -D${item}"
done

java -classpath '.:lib/*' ${argument_string} ai.labs.api.ApiServer
