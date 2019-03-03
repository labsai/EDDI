#!/bin/bash

env_vars=($(printenv | grep EDDI_JAVA_ENV_))

argument_string=""

for item in ${env_vars[*]}
do
    value=${item#*=}
    argument_string="${argument_string} -D${value}"
done

echo "dynamically set java arguments: ${argument_string}"

memory_string=""

if ! [[ -z "${EDDI_MEMORY_MIN}" ]]; then
    memory_string="${memory_string} -Xms${EDDI_MEMORY_MIN}"
fi

if ! [[ -z "${EDDI_MEMORY_MAX}" ]]; then
    memory_string="${memory_string} -Xmx${EDDI_MEMORY_MAX} -XX:NewSize=${EDDI_MEMORY_MAX}"
fi

echo "memory params: ${memory_string}"

java -server ${memory_string} -classpath '.:lib/*' -DEDDI_ENV=$EDDI_ENV ${argument_string} --add-opens java.base/java.lang=ALL-UNNAMED ai.labs.api.ApiServer
