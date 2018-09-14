REM used in docker container

@echo off
if [%1] == [] (
    echo "Usage: <environment>"
    goto end
)

if  %1==production (
SET JAVA_OPTS= ^
        -server -Xms1024m -Xmx1024m ^
        -XX:+CMSParallelRemarkEnabled ^
        -XX:+UseCMSInitiatingOccupancyOnly ^
        -XX:CMSInitiatingOccupancyFraction=50 ^
        -XX:CMSWaitDuration=300000 ^
        -XX:+CMSScavengeBeforeRemark ^
        -XX:+ScavengeBeforeFullGC ^
        -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=`date`.hprof ^
) else if %1==development (
	SET JAVA_OPTS=
) else (
	echo "Invalid environment %1"
	goto end
)

java %JAVA_OPTS% -classpath .;lib/* -DEDDI_ENV=%1 ai.labs.api.ApiServer
:end