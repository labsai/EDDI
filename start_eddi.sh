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

if ! [[ -z "${EDDI_MEMORY_PERCENTAGE_MIN}" ]]; then
    memory_string="${memory_string} -XX:MinRAMPercentage=${EDDI_MEMORY_PERCENTAGE_MIN}"
fi

if ! [[ -z "${EDDI_MEMORY_PERCENTAGE_MAX}" ]]; then
    memory_string="${memory_string} -XX:MaxRAMPercentage=${EDDI_MEMORY_PERCENTAGE_MAX}"
fi


echo "memory params: ${memory_string}"



# enable additional JVM options
jvm_options=""
if ! [[ -z "${EDDI_JVM_OPTIONS}" ]]; then
    jvm_options=${EDDI_JVM_OPTIONS}
fi



java -server ${memory_string} \
${jvm_options} \
-classpath '.:lib/*' \
-DEDDI_ENV=$EDDI_ENV ${argument_string} \
--add-opens java.base/java.lang=ALL-UNNAMED \
-Dcom.sun.management.jmxremote \
-Dcom.sun.management.jmxremote.port=9001 \
-Dcom.sun.management.jmxremote.ssl=false \
-Dcom.sun.management.jmxremote.authenticate=false \
ai.labs.api.ApiServer
