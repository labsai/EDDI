#!/bin/sh
java -server -Xms1024m -Xmx1024m -XX:+CMSParallelRemarkEnabled -XX:+CMSScavengeBeforeRemark -XX:+UseCMSInitiatingOccupancyOnly -XX:CMSInitiatingOccupancyFraction=50 -XX:CMSWaitDuration=300000 -XX:GCTimeRatio=40 -XX:MaxPermSize=256m -classpath '.:lib/*' -DEDDI_ENV=production io.sls.core.CoreServer
