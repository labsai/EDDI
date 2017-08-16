#!/usr/bin/env bash

env_vars=($(printenv | grep EDDI_JAVA_ENV_))

argument_string=""

for item in ${env_vars[*]}
do
    value=${item#*=}
    argument_string="${argument_string} -D${value}"
done

echo "dynamically set java arguments: ${argument_string}"

java -server -XX:+UseG1GC -Xms$EDDI_MIN_MEMORY -Xmx$EDDI_MAX_MEMORY -classpath '.:lib/*' -DEDDI_ENV=$EDDI_ENV ${argument_string} ai.labs.api.ApiServer
