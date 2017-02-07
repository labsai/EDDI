@echo off
if [%1] == [] (
    echo "Usage: <environment> [rmi-server-hostname]"
    goto end
)

if  %1==production (
SET JAVA_OPTS= ^
        -server -Xms512m -Xmx512m ^
        -XX:+UseConcMarkSweepGC ^
        -XX:+CMSParallelRemarkEnabled ^
        -XX:+UseCMSInitiatingOccupancyOnly ^
        -XX:CMSInitiatingOccupancyFraction=50 ^
        -XX:CMSWaitDuration=300000 ^
        -XX:+CMSScavengeBeforeRemark ^
        -XX:+ScavengeBeforeFullGC ^
        -XX:+PrintGCDateStamps -verbose:gc -XX:+PrintGCDetails -Xloggc:gc.log ^
        -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=100M ^
        -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=`date`.hprof ^
        -Djava.rmi.server.hostname=%2 ^
        -Dcom.sun.management.jmxremote.port=9010 ^
        -Dcom.sun.management.jmxremote.authenticate=false ^
        -Dcom.sun.management.jmxremote.ssl=false
) else if %1==development (
	SET JAVA_OPTS=
) else (
	echo "Invalid environment %1"
	goto end
)

java %JAVA_OPTS% -classpath .;lib/* -DEDDI_ENV=%1 ai.labs.configuration.ConfigurationServer
:end